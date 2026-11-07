# Magpie-Mobile（P1 界面阶段）

目标与完整计划见 plan.md。当前：**Compose 原生界面 + GatewayService 前台服务**。

## 架构
- **core/**（纯 JVM，23 组件 98 测试）：管理 API 客户端、网关配置、聊天栈（非流式+SSE 解析+会话）、引擎路径/执行/监督、插件脚本受控安装、凭据脱敏、FGS 策略、P0-2 root 验证脚本。
- **app/**：GatewayService（specialUse FGS，Android 14+ 决策、启动自检、崩溃退避、用户停止不复活）+ Compose 仪表盘（状态头/网关启停/模型目录/聊天测试/诊断/日志）。
- 载荷：APK 内嵌 `libmagpie_exec.so`（Magpie CLI 0.1.3188）与 `libbun_exec.so`（Bun 1.4.2），经 nativeLibraryDir exec（P0-2 真机已验证）。

## 构建
- CI：推送到 main 触发构建，APK 自动发布到 Release v0.0.1-poc。
- 资产不入库：scripts/fetch-assets.sh 按官方 SHA256 下载校验。
- 本地构建：Android Studio 打开；需 JDK 17。

## P1 真机验收清单
1. 下载 Release v0.0.1-poc 的 app-debug.apk，覆盖安装。
2. 打开：Compose 卡片式仪表盘；状态头应显示「就绪（网关未运行）」。
3. 点「启动网关」：出现常驻通知「Magpie 网关运行中」。
4. 点「停止网关」：通知消失。
5. 「一键诊断」：magpie 0.1.3188 / bun 1.4.2 exit=0（P0-2 已验证）。
6. 模型目录/聊天测试在网关未运行时报错属预期（P2 接核心后可用）。

## 已知边界（P1）
- 网关 key 为本地生成占位；核心真 key 接入在 P2。
- 聊天默认模型写死 deepseek/deepseek-chat；动态选择在 P2。
- SSE 流式 UI 在 P2（解析器已就绪）。
