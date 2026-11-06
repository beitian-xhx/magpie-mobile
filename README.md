# Magpie-Mobile（P0 验证阶段）

目标与完整计划见 plan.md。本仓库当前内容：P0-2 最小验证 APK——
验证 Magpie Android CLI 与 Bun 1.4.2 以 ELF PIE 载荷内嵌 APK、经 nativeLibraryDir exec 启动。

## 构建
- CI：推送到 main 即触发 .github/workflows/build.yml，产物 app-debug.apk（Actions Artifacts）。
- 资产不入库：scripts/fetch-assets.sh 按 usemagpie.ai / Magpie bunSums 的官方 SHA256 下载校验。
- 本地构建：Android Studio 打开即可；需 JDK 17。

## P0-2 真机验收清单
1. 安装 app-debug.apk（侧载）。
2. 点「运行 magpie --version」/「运行 bun --version」，记录 exit 与输出。
3. Android 10（4KB 页）与 16KB 设备各测一台；记录 设备型号/API/页大小/输出截图。
4. 失败时完整记录 stderr 与 logcat（chmod/exec 相关）。
