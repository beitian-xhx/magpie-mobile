#!/usr/bin/env python3
"""Apply a hash-pinned, exact mobile policy package; never invoke Go/build tools."""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import subprocess
import sys
import tempfile
from dataclasses import dataclass

SOURCE_REVISION = "025123431c140a4988efc3d5c44546e8d73d16d3"
GO_VERSION = "1.26.8"
DEFAULT_PATCH_ROOT = Path(__file__).resolve().parent.parent / "patches" / "mobile"


class PatchError(ValueError):
    """A source/package mismatch; no fuzzy patch or partial fallback is allowed."""


def digest(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def safe_path(root: Path, name: str) -> Path:
    """Reject traversal and every symlink component, including destination files."""
    if not isinstance(name, str) or not name or "\\" in name:
        raise PatchError("invalid package path")
    rel = PurePosixPath(name)
    if rel.is_absolute() or any(p in ("", ".", "..") for p in name.split("/")):
        raise PatchError(f"unsafe package path: {name}")
    p = root
    for component in rel.parts:
        p = p / component
        if p.is_symlink():
            raise PatchError(f"symlink is not allowed: {name}")
    if not p.resolve().is_relative_to(root.resolve()):
        raise PatchError(f"path escapes root: {name}")
    return p


def read_regular(root: Path, name: str) -> bytes:
    p = safe_path(root, name)
    if not p.is_file():
        raise PatchError(f"missing regular file: {name}")
    return p.read_bytes()


def require_hash(data: bytes, expected: str, name: str) -> None:
    if not isinstance(expected, str) or not re.fullmatch(r"[0-9a-f]{64}", expected):
        raise PatchError(f"invalid SHA256 in manifest: {name}")
    if digest(data) != expected:
        raise PatchError(f"SHA256 mismatch: {name}; only the pinned source/package is supported")


@dataclass(frozen=True)
class Change:
    path: Path
    before: bytes | None
    after: bytes
    mode: int


def plan(source_root: Path, patch_root: Path = DEFAULT_PATCH_ROOT) -> list[Change]:
    source_root = source_root.absolute()
    patch_root = patch_root.absolute()
    if any(p.is_symlink() for root in (source_root, patch_root) for p in (root, *root.parents)):
        raise PatchError("roots and their parents must not be symlinks")
    if not source_root.is_dir() or not patch_root.is_dir():
        raise PatchError("source-root and patch-root must be existing directories")
    manifest = json.loads(read_regular(patch_root, "manifest.json"))
    if not isinstance(manifest, dict):
        raise PatchError("manifest must be an object")
    if manifest.get("schemaVersion") != 1 or manifest.get("sourceRevision") != SOURCE_REVISION:
        raise PatchError("unsupported manifest schema or source revision")
    if manifest.get("goVersion") != GO_VERSION:
        raise PatchError("unsupported Go version; expected 1.26.8")
    # Git checkouts must really be the pinned commit. Archives are accepted only
    # through the same full-file hashes, never by a caller's revision assertion.
    if (source_root / ".git").exists():
        result = subprocess.run(["git", "-C", str(source_root), "rev-parse", "HEAD"],
                                check=False, capture_output=True, text=True)
        if result.returncode or result.stdout.strip() != SOURCE_REVISION:
            raise PatchError("git HEAD is not the pinned source revision")
    checks = manifest.get("checks", [])
    if not any(c.get("path") == "go.mod" for c in checks):
        raise PatchError("manifest must pin go.mod")
    for check in checks:
        require_hash(read_regular(source_root, check["path"]), check["sha256"], check["path"])
    go_mod = read_regular(source_root, "go.mod").decode("utf-8")
    if not re.search(r"(?m)^go " + re.escape(GO_VERSION) + r"$", go_mod):
        raise PatchError("source go.mod does not require Go 1.26.8")
    changes = []
    destinations = set()
    for patch in manifest.get("patches", []):
        name = patch["path"]
        p = safe_path(source_root, name)
        if name in destinations:
            raise PatchError(f"duplicate destination: {name}")
        destinations.add(name)
        before = read_regular(source_root, name)
        require_hash(before, patch["sha256"], name)
        after = before.decode("utf-8")
        if not patch.get("replacements"):
            raise PatchError(f"no exact replacements: {name}")
        for i, replacement in enumerate(patch["replacements"]):
            old, new = replacement["old"], replacement["new"]
            if not isinstance(old, str) or not old or not isinstance(new, str) or after.count(old) != 1:
                raise PatchError(f"exact replacement must match once: {name} replacement {i}")
            after = after.replace(old, new, 1)
        output = after.encode("utf-8")
        if "resultSha256" in patch:
            require_hash(output, patch["resultSha256"], name + " (result)")
        changes.append(Change(p, before, output, p.stat().st_mode & 0o777))
    overlays = manifest.get("overlays", [])
    if not changes or not overlays:
        raise PatchError("empty patch/overlay package")
    overlay_root = safe_path(patch_root, "overlay")
    actual_overlay = set()
    for p in overlay_root.rglob("*"):
        if p.is_symlink():
            raise PatchError("overlay must not contain symlinks")
        if p.is_file():
            actual_overlay.add(p.relative_to(overlay_root).as_posix())
    declared_overlay = {o["path"] for o in overlays}
    if actual_overlay != declared_overlay:
        raise PatchError("overlay inventory mismatch")
    for overlay in overlays:
        name = overlay["path"]
        if name in destinations:
            raise PatchError(f"duplicate destination: {name}")
        destinations.add(name)
        p = safe_path(source_root, name)
        if p.exists():
            raise PatchError(f"overlay destination already exists: {name}")
        after = read_regular(overlay_root, name)
        require_hash(after, overlay["sha256"], "overlay/" + name)
        changes.append(Change(p, None, after, 0o644))
    return changes


def atomic_write(path: Path, data: bytes, mode: int) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    fd, temporary = tempfile.mkstemp(prefix=".mobile-patch-", dir=path.parent)
    try:
        with os.fdopen(fd, "wb") as stream:
            stream.write(data)
            stream.flush()
            os.fsync(stream.fileno())
        os.chmod(temporary, mode)
        os.replace(temporary, path)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)


def apply(changes: list[Change]) -> None:
    """Preflight every file again; roll back completed writes on an I/O failure."""
    for change in changes:
        if any(p.is_symlink() for p in (change.path, *change.path.parents)):
            raise PatchError("destination or parent changed into a symlink")
        current = change.path.read_bytes() if change.path.exists() else None
        if current != change.before:
            raise PatchError("source changed after preflight")
    done = []
    try:
        for change in changes:
            atomic_write(change.path, change.after, change.mode)
            done.append(change)
    except OSError:
        for change in reversed(done):
            if change.before is None:
                change.path.unlink()
            else:
                atomic_write(change.path, change.before, change.mode)
        raise


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source-root", required=True, type=Path, help="pinned Magpie checkout/archive root")
    parser.add_argument("--patch-root", type=Path, default=DEFAULT_PATCH_ROOT, help="trusted manifest/overlay package root")
    parser.add_argument("--check", action="store_true", help="validate and plan only; write nothing")
    args = parser.parse_args(argv)
    try:
        changes = plan(args.source_root, args.patch_root)
        if not args.check:
            apply(changes)
    except (PatchError, OSError, ValueError, KeyError, TypeError) as err:
        print(f"mobile patch rejected: {err}", file=sys.stderr)
        return 1
    print(f"mobile patch {'verified' if args.check else 'applied'}: {SOURCE_REVISION}; Go {GO_VERSION}; {len(changes)} files; no build executed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
