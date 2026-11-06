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
  curl -fL --retry 2 --connect-timeout 15 --max-time 600 -o "$out" "$url" && return 0
  for m in https://gh-proxy.com/ https://ghproxy.net/; do
    curl -fL --retry 1 --connect-timeout 15 --max-time 600 -o "$out" "$m$url" && return 0
  done
  return 1
}

verify() { # verify <file> <expected>
  echo "$2  $1" | sha256sum -c - >/dev/null || { echo "哈希不匹配: $1" >&2; exit 1; }
}

if [ ! -f build-tmp/magpie-cli ]; then
  fetch build-tmp/magpie-cli "$MAGPIE_URL"
fi
verify build-tmp/magpie-cli "$MAGPIE_SHA256"
cp build-tmp/magpie-cli "$DEST/libmagpie_exec.so"

if [ ! -f build-tmp/bun.zip ]; then
  fetch build-tmp/bun.zip "$BUN_URL"
fi
verify build-tmp/bun.zip "$BUN_ZIP_SHA256"
rm -rf build-tmp/bun-extract && mkdir -p build-tmp/bun-extract
unzip -q -o build-tmp/bun.zip -d build-tmp/bun-extract
BUN_BIN=$(find build-tmp/bun-extract -type f -name bun | head -1)
[ -n "$BUN_BIN" ] || { echo "zip 内未找到 bun" >&2; exit 1; }
cp "$BUN_BIN" "$DEST/libbun_exec.so"

chmod +x "$DEST"/*.so
sha256sum "$DEST"/*.so
echo "资产就绪:"
ls -l "$DEST"
