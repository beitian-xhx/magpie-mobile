#!/usr/bin/env python3
"""Offline application tests. No Go invocation, model call or paid API request."""
import contextlib
import copy
import importlib.util
import io
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest import mock

SCRIPT = Path(__file__).with_name("apply-mobile-core.py")
spec = importlib.util.spec_from_file_location("apply_mobile_core", SCRIPT)
patcher = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = patcher
spec.loader.exec_module(patcher)


class ApplyMobileCoreTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        root = Path(self.temporary.name)
        self.source = root / "source"
        self.package = root / "package"
        self.source.mkdir()
        self.package.mkdir()
        self.original = b"package sample\nconst Old = true\n"
        self.go_mod = b"module github.com/yetone/magpie\n\ngo 1.26.8\n"
        (self.source / "go.mod").write_bytes(self.go_mod)
        (self.source / "internal").mkdir()
        (self.source / "internal/sample.go").write_bytes(self.original)
        (self.package / "overlay/internal/mobilepolicy").mkdir(parents=True)
        self.overlay = b"//go:build mobile\n\npackage mobilepolicy\nconst Enabled = true\n"
        (self.package / "overlay/internal/mobilepolicy/mobile.go").write_bytes(self.overlay)
        self.manifest = {
            "schemaVersion": 1,
            "sourceRevision": patcher.SOURCE_REVISION,
            "goVersion": "1.26.8",
            "checks": [{"path": "go.mod", "sha256": patcher.digest(self.go_mod)}],
            "patches": [{"path": "internal/sample.go", "sha256": patcher.digest(self.original),
                         "resultSha256": patcher.digest(self.original.replace(b"const Old", b"const New")),
                         "replacements": [{"old": "const Old", "new": "const New"}]}],
            "overlays": [{"path": "internal/mobilepolicy/mobile.go", "sha256": patcher.digest(self.overlay)}],
        }
        self.save_manifest()

    def save_manifest(self):
        (self.package / "manifest.json").write_text(json.dumps(self.manifest), encoding="utf-8")

    def plan(self):
        return patcher.plan(self.source, self.package)

    def unchanged(self):
        self.assertEqual((self.source / "internal/sample.go").read_bytes(), self.original)
        self.assertFalse((self.source / "internal/mobilepolicy/mobile.go").exists())

    def rejected(self):
        with self.assertRaises(patcher.PatchError):
            self.plan()
        self.unchanged()

    def test_plan_is_read_only_and_apply_copies_overlay(self):
        changes = self.plan()
        self.assertEqual(len(changes), 2)
        self.unchanged()
        patcher.apply(changes)
        self.assertEqual((self.source / "internal/sample.go").read_bytes(), self.original.replace(b"const Old", b"const New"))
        self.assertEqual((self.source / "internal/mobilepolicy/mobile.go").read_bytes(), self.overlay)

    def test_check_cli_does_not_write_or_invoke_build(self):
        with contextlib.redirect_stdout(io.StringIO()), mock.patch.object(patcher.subprocess, "run") as process:
            result = patcher.main(["--source-root", str(self.source), "--patch-root", str(self.package), "--check"])
        self.assertEqual(result, 0)
        process.assert_not_called()
        self.unchanged()

    def test_baseline_and_schema_fail_closed(self):
        for field, wrong in (("sourceRevision", "0" * 40), ("schemaVersion", 2), ("goVersion", "1.26.7")):
            original = self.manifest[field]
            self.manifest[field] = wrong
            self.save_manifest()
            self.rejected()
            self.manifest[field] = original
        self.save_manifest()

    def test_pinned_source_sha_is_required_even_if_text_matches(self):
        p = self.source / "internal/sample.go"
        p.write_bytes(self.original + b"// drift\n")
        with self.assertRaisesRegex(patcher.PatchError, "SHA256 mismatch"):
            self.plan()
        self.assertEqual(p.read_bytes(), self.original + b"// drift\n")
        self.assertFalse((self.source / "internal/mobilepolicy/mobile.go").exists())

    def test_go_mod_sha_and_exact_version_required(self):
        (self.source / "go.mod").write_bytes(self.go_mod.replace(b"1.26.8", b"1.26.7"))
        self.rejected()
        self.manifest["checks"][0]["sha256"] = patcher.digest((self.source / "go.mod").read_bytes())
        self.save_manifest()
        self.rejected()

    def test_missing_duplicate_empty_and_nonliteral_replacements_rejected(self):
        for old in ("missing", "", "const.*Old"):
            self.manifest["patches"][0]["replacements"][0]["old"] = old
            self.save_manifest()
            self.rejected()
        p = self.source / "internal/sample.go"
        twice = self.original + b"const Old = false\n"
        p.write_bytes(twice)
        self.manifest["patches"][0]["sha256"] = patcher.digest(twice)
        self.manifest["patches"][0]["replacements"][0]["old"] = "const Old"
        self.save_manifest()
        with self.assertRaisesRegex(patcher.PatchError, "match once"):
            self.plan()
        self.assertEqual(p.read_bytes(), twice)

    def test_result_sha_required_when_declared(self):
        self.manifest["patches"][0]["resultSha256"] = "0" * 64
        self.save_manifest()
        self.rejected()

    def test_overlay_sha_inventory_and_existing_destination_rejected(self):
        p = self.package / "overlay/internal/mobilepolicy/mobile.go"
        p.write_bytes(self.overlay + b"// drift\n")
        self.rejected()
        p.write_bytes(self.overlay)
        (self.package / "overlay/extra.go").write_text("unexpected", encoding="utf-8")
        self.rejected()
        (self.package / "overlay/extra.go").unlink()
        destination = self.source / "internal/mobilepolicy/mobile.go"
        destination.parent.mkdir()
        destination.write_bytes(b"existing")
        with self.assertRaisesRegex(patcher.PatchError, "already exists"):
            self.plan()
        self.assertEqual(destination.read_bytes(), b"existing")
        self.assertEqual((self.source / "internal/sample.go").read_bytes(), self.original)

    def test_path_traversal_absolute_backslash_and_symlinks_rejected(self):
        for name in ("../outside", "/tmp/outside", "internal/../outside", "internal//sample.go", "internal\\sample.go"):
            self.manifest["patches"][0]["path"] = name
            self.save_manifest()
            self.rejected()
        self.manifest["patches"][0]["path"] = "internal/sample.go"
        self.save_manifest()
        overlay = self.package / "overlay/internal/mobilepolicy/mobile.go"
        overlay.unlink()
        overlay.symlink_to(self.source / "internal/sample.go")
        self.rejected()

    def test_duplicate_destination_rejected(self):
        self.manifest["patches"].append(copy.deepcopy(self.manifest["patches"][0]))
        self.save_manifest()
        self.rejected()

    def test_git_checkout_must_match_pinned_head(self):
        (self.source / ".git").mkdir()
        with mock.patch.object(patcher.subprocess, "run", return_value=mock.Mock(returncode=0, stdout="bad-head\n")):
            self.rejected()
        with mock.patch.object(patcher.subprocess, "run", return_value=mock.Mock(returncode=0, stdout=patcher.SOURCE_REVISION + "\n")) as command:
            self.assertEqual(len(self.plan()), 2)
            self.assertEqual(command.call_args[0][0], ["git", "-C", str(self.source), "rev-parse", "HEAD"])

    def test_no_reapply_or_mixed_state_fallback(self):
        patcher.apply(self.plan())
        with self.assertRaisesRegex(patcher.PatchError, "SHA256 mismatch"):
            self.plan()

    def test_source_change_after_preflight_rejected(self):
        changes = self.plan()
        changed = self.original + b"// concurrent drift\n"
        (self.source / "internal/sample.go").write_bytes(changed)
        with self.assertRaisesRegex(patcher.PatchError, "changed after preflight"):
            patcher.apply(changes)
        self.assertEqual((self.source / "internal/sample.go").read_bytes(), changed)
        self.assertFalse((self.source / "internal/mobilepolicy/mobile.go").exists())

    def test_symlink_parent_after_preflight_rejected(self):
        changes = self.plan()
        internal = self.source / "internal"
        elsewhere = self.source / "relocated"
        internal.rename(elsewhere)
        internal.symlink_to(elsewhere, target_is_directory=True)
        with self.assertRaisesRegex(patcher.PatchError, "symlink"):
            patcher.apply(changes)
        self.assertEqual((elsewhere / "sample.go").read_bytes(), self.original)
        self.assertFalse((elsewhere / "mobilepolicy/mobile.go").exists())

    def test_write_failure_rolls_back_prior_file(self):
        changes = self.plan()
        real = patcher.atomic_write
        calls = 0
        def fail_second(path, data, mode):
            nonlocal calls
            calls += 1
            if calls == 2:
                raise OSError("synthetic I/O failure")
            return real(path, data, mode)
        with mock.patch.object(patcher, "atomic_write", side_effect=fail_second):
            with self.assertRaises(OSError):
                patcher.apply(changes)
        self.unchanged()

    def test_mobile_cli_print_branch_is_keyless_and_before_desktop_slice(self):
        root = patcher.DEFAULT_PATCH_ROOT
        manifest = json.loads((root / "manifest.json").read_text(encoding="utf-8"))
        cli = next(p for p in manifest["patches"] if p["path"] == "internal/cli/web_cli.go")
        branch = next(r for r in cli["replacements"] if r["old"] == "func printWeb(w *gui.Web) {\n")
        self.assertIn("if mobilepolicy.Enabled", branch["new"])
        self.assertIn("w.Addr", branch["new"])
        self.assertIn("App-managed credentials", branch["new"])
        self.assertIn("return", branch["new"])
        for unsafe in ("w.Link", "strings.Index", "proxyLink(", "NetworkLinks(", "the link carries"):
            self.assertNotIn(unsafe, branch["new"])
        self.assertRegex(cli["resultSha256"], "^[0-9a-f]{64}$")
        tests = (root / "overlay/internal/cli/mobile_policy_test.go").read_text(encoding="utf-8")
        self.assertTrue(tests.startswith("//go:build mobile\n"))
        self.assertIn("func TestMobilePrintWebNoKeyDoesNotPanic", tests)
        self.assertIn("func TestMobilePrintWebDoesNotOutputCredentials", tests)

    def test_provider_revision_and_raw_edit_guards_are_packaged(self):
        root = patcher.DEFAULT_PATCH_ROOT
        manifest = json.loads((root / "manifest.json").read_text(encoding="utf-8"))
        patch = next(p for p in manifest["patches"] if p["path"] == "internal/provider/provider.go")
        self.assertEqual(len(patch["replacements"]), 7)
        inserts = "\n".join(r["new"] for r in patch["replacements"])
        self.assertIn("mobileRevision", inserts)
        self.assertIn("mobileProviderJSON", inserts)
        self.assertIn("mobileStore(f)", inserts)
        store = (root / "overlay/internal/provider/mobile_store.go").read_text(encoding="utf-8")
        for guard in ("mobileAcquireLock", "if !held", "f.mobileRevision != old.mobileRevision", "edit.PatchJSON", "p := f.Providers[i].clone()", "hmac.Equal", "append([]string{}, p.Models...)", "mobileUnsafeComposite(before[key])", "mobileUnsafeComposite(value)", "mobileUnambiguousJSON(plain)", "mobileCanonicalSpelling(plain)", "depth > 64"):
            self.assertIn(guard, store)
        self.assertNotIn("Save(p)", store)
        http = (root / "overlay/internal/gui/mobile_providers.go").read_text(encoding="utf-8")
        self.assertIn("strict.DisallowUnknownFields()", http)
        self.assertIn("seen[name]", http)
        self.assertIn("provider_revision_conflict", http)
        self.assertIn("provider_write_unknown", http)
        policy = (root / "overlay/internal/gui/mobile_policy.go").read_text(encoding="utf-8")
        self.assertIn("providerSchemaVersion", policy)
        self.assertIn("providerManagement", policy)
        self.assertNotIn("provider/test", policy)

    def test_read_only_model_discovery_guards_are_packaged(self):
        root = patcher.DEFAULT_PATCH_ROOT
        source = (root / "overlay/internal/provider/mobile_discovery.go").read_text(encoding="utf-8")
        for guard in ("http.MethodGet", "mobileModelsTimeout = 8 * time.Second", "mobileModelsBytes = 2 << 20", "mobileModelsCount = 1000", "http.ErrUseLastResponse", "mobileCanonical()", "hmac.Equal", "oldOrigin != newOrigin", "http.StatusNotFound && index+1 < len(bases)", "io.LimitReader(response.Body, mobileModelsBytes+1)", "mobileModelsContainKey", "DisableCompression: true"):
            self.assertIn(guard, source)
        for unsafe in ("http.MethodPost", "MobileEditProvider(", "MobileCreateProvider(", "mobileWrite(", "Save(", "Test(", "Detect("):
            self.assertNotIn(unsafe, source)
        routes = (root / "overlay/internal/gui/mobile_providers.go").read_text(encoding="utf-8")
        self.assertIn("/api/mobile/providers/models", routes)
        self.assertIn("[]string{\"chat\", \"key\", \"id\", \"revision\"}", routes)
        policy = (root / "overlay/internal/gui/mobile_policy.go").read_text(encoding="utf-8")
        self.assertIn("providerModelDiscovery", policy)

    def test_real_package_inventory_and_policy_guards(self):
        root = patcher.DEFAULT_PATCH_ROOT
        manifest = json.loads((root / "manifest.json").read_text(encoding="utf-8"))
        self.assertEqual(manifest["sourceRevision"], patcher.SOURCE_REVISION)
        self.assertEqual(manifest["goVersion"], "1.26.8")
        self.assertEqual(len(manifest["patches"]), 8)
        for overlay in manifest["overlays"]:
            data = (root / "overlay" / overlay["path"]).read_bytes()
            patcher.require_hash(data, overlay["sha256"], overlay["path"])
        self.assertEqual({p.relative_to(root / "overlay").as_posix() for p in (root / "overlay").rglob("*.go")},
                         {o["path"] for o in manifest["overlays"]})
        lan = next(p for p in manifest["patches"] if p["path"].endswith("/lan.go"))
        inserts = "\n".join(r["new"] for r in lan["replacements"])
        self.assertIn("access.Authenticate(k)", inserts)
        self.assertIn("mobilepolicy.Enabled || !local(r)", inserts)
        for exception in ("ownCallback", "corsPreflight", "keyPageOpen", "imageTokenAsked", "oidcExchange", "proveAsked"):
            self.assertRegex(inserts, "!mobilepolicy.Enabled.*" + exception)
        tests = [(root / "overlay" / o["path"]).read_text() for o in manifest["overlays"] if o["path"].endswith("_test.go")]
        self.assertEqual(len(tests), 7)
        self.assertTrue(all(t.startswith("//go:build mobile\n") for t in tests))
        self.assertGreaterEqual(sum(t.count("func TestMobile") for t in tests), 10)


if __name__ == "__main__":
    unittest.main()
