# P0-1 资产获取与静态验证报告（2026-11-06）

- 基线：Magpie v0.1.3188 / commit 54dc257f100e2ca82a843b1eea6a24b8006781f5；Bun 1.4.2
- 性质：纯静态只读检查 + 哈希校验；未执行任何二进制。真机执行验证属 P0-2。
- 下载通道：GitHub 直连被拒（HTTP 400，环境网络策略），proxy.golang.org 超时。改用镜像与 npm 通道取得，全部以官方公布校验值为准（镜像仅作传输，与官方 install.sh 的 mirror 机制同思路）。

## 1. 资产与校验（全部通过）

| 资产 | 大小(B) | 来源 | 校验依据 | 结果 |
|---|---|---|---|---|
| magpie-cli-android-arm64 | 70,910,248 | gh-proxy 镜像 → GitHub release v0.1.3188 | usemagpie.ai/api/latest 的 sha256=5b0a793c…1fc26 | ✅ 完全一致 |
| bun-linux-aarch64-android.zip | 35,154,316 | gh-proxy 镜像 → oven-sh release bun-v1.4.2 | Magpie bunSums 的 sha256=a1c7e298…0274 | ✅ 完全一致 |
| @oven/bun-linux-aarch64-android@1.4.2 tgz | 36,383,614 | npm registry（备用独立通道） | npm dist.integrity (sha512-3mZKO2…) | ✅ 一致；其内 bun 与 zip 版字节级同为 86,800,440 |

注：zip 版与 npm 版 Bun 二进制大小完全相同（86,800,440 B），两条独立通道互证。

## 2. 静态门槛结果（magpie CLI 与 Bun 全部通过）

| 门槛 | magpie-cli-android-arm64 | bun-android-arm64 |
|---|---|---|
| G1 AArch64 | ✅ ELF64/AArch64 | ✅ ELF64/AArch64 |
| G2 PIE | ✅ ET_DYN | ✅ ET_DYN |
| G3 bionic 链接器 | ✅ PT_INTERP=/system/bin/linker64 | ✅ PT_INTERP=/system/bin/linker64 |
| G4 无 glibc 依赖 | ✅ 静态，无 DT_NEEDED | ✅ 仅 libc.so/libm.so/libdl.so（bionic 命名，非 glibc 的 .so.6） |
| G5 PT_LOAD ≥16KB 对齐 | ✅ 最小 0x10000（64KB） | ✅ 最小 0x4000（16KB） |
| G6 无 Termux 硬编码 | ✅ com.termux 引用 0 次 | ✅ com.termux 引用 0 次 |

其他观察：两者 GNU_STACK 均不可执行（W^X 友好）；Bun 的 Android note 记录 NDK r27c / build 12479018。

## 3. 对计划“未验证项”的更新

plan.md §2.4 与风险表中的以下“未验证”现在在**静态层面已解除**：
- Bun Android 构建是否硬编码 Termux 解释器 → 否，使用系统 bionic linker64。
- 是否依赖 glibc → 否。
- 16KB 页对齐 → 两载荷均满足（Bun 恰为 16KB，magpie 为 64KB）。
- PT_INTERP/DT_NEEDED/PIE → 全部符合 APK nativeLibraryDir 执行路线要求。

**仍未验证（需要真机）**：实际 execve 启动、argv0 行为、TLS/DNS/代理、Node API、插件运行、Bun 最低 Android API 的运行时表现、OEM 差异。这些按计划属 P0-2。

## 4. 结论

P0-1 通过。按 plan.md 第 6 节进入 P0-2：最小 release APK + 真机执行验证。P0-2 需要可用的构建环境（Android SDK/Gradle）与测试设备；容器内构建能力探测见 §5。

## 5. 本容器构建能力探测

见报告生成时输出（gradle/java/Android SDK 是否存在）。容器无 SDK 时，P0-2 由用户构建环境执行，本项目提供完整 Gradle 工程与验收清单。

## 6. 资产清单

- p0/bin/magpie-cli-android-arm64（哈希匹配官方）
- p0/zip/bun-linux-aarch64-android.zip（哈希匹配 Magpie bunSums）
- p0/npm/bun-android.tgz（npm integrity 匹配；备用通道）
- p0/bin/bun-android-arm64、p0/bin/bun-android-arm64-npm（从上述两个包解出的同一 ELF）
- p0/inspect.json（完整机器可读结果）
- p0/checksums.expected.txt（预期值）
