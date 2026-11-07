#!/usr/bin/env bash
# P0-2 CI：按官方哈希获取 Magpie CLI 与 Bun Android ARM64，放入 jniLibs。
# 镜像仅作传输通道，校验值来自官方（与 usemagpie.ai install.sh 的 mirror 机制同思路）。
set -euo pipefail
cd "$(dirname "$0")/.."
DEST=app/src/main/jniLibs/arm64-v8a
mkdir -p "$DEST" build-tmp

MAGPIE_SHA256=5b0a793cd85ad171e15ecb9782621adcaf43e2e988180d0db32a1542b9c1fc26
BUN_ZIP_SHA256=a1c7e2983f1bb65146beb256a4d72449f23042412bc2cf278aa6397ba27e0274
MAGPIE_URL=https://github.com/yetone/magpie-releases/releases/download/v0.1.3188/magpie-cli-android-arm64
BUN_URL=https://github.com/oven-sh/bun/releases/download/bun-v1.4.2/bun-linux-aarch64-android.zip

fetch() { # fetch <out> <url>
  local out=$1 url=$2
  # 注意：脚本运行在 set -e 下，命令失败必须先 || true 抑制，
  # 否则 && 链失败会直接触发 errexit，镜像回退永远不会执行。
  if curl -fL --retry 2 --connect-timeout 15 --max-time 600 -o "$out" "$url"; then return 0; fi
  local m
  for m in https://gh-proxy.com/ https://ghproxy.net/; do
    if curl -fL --retry 1 --connect-timeout 15 --max-time 600 -o "$out" "$m$url"; then return 0; fi
  done
  return 1
}

verify() { # verify <file> <expected>
  echo "$2  $1" | sha256sum -c - >/dev/null || { echo "哈希不匹配: $1" >&2; exit 1; }
}

case "${1:-}" in
  "")
    if [ ! -f build-tmp/magpie-cli ]; then fetch build-tmp/magpie-cli "$MAGPIE_URL"; fi
    verify build-tmp/magpie-cli "$MAGPIE_SHA256"
    cp build-tmp/magpie-cli "$DEST/libmagpie_exec.so"
    ;;
  --bun-only) ;; # CLI is rebuilt from the pinned source plus verified mobile patch manifest
  *) echo "Unsupported option" >&2; exit 2 ;;
esac

if [ ! -f build-tmp/bun.zip ]; then
  fetch build-tmp/bun.zip "$BUN_URL"
fi
verify build-tmp/bun.zip "$BUN_ZIP_SHA256"
python3 - <<'PY'
from pathlib import Path
import zipfile
with zipfile.ZipFile("build-tmp/bun.zip") as archive:
    entries = [item for item in archive.infolist() if not item.is_dir() and item.filename.rsplit("/", 1)[-1] == "bun"]
    if len(entries) != 1:
        raise SystemExit("Expected exactly one Bun executable in verified archive")
    Path("app/src/main/jniLibs/arm64-v8a/libbun_exec.so").write_bytes(archive.read(entries[0]))
PY

chmod +x "$DEST"/*.so
sha256sum "$DEST"/*.so
echo "资产就绪:"
ls -l "$DEST"
