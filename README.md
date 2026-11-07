# Magpie-Mobile（P2 界面阶段）

目标与完整计划见 plan.md。当前：**Compose 原生界面 + GatewayService 前台服务 + 流式聊天**。

## 架构
- **core/**（纯 JVM，25 组件 105 测试）：管理 API 客户端、网关配置、聊天栈（非流式+流式+SSE 解析+会话+降级）、引擎路径/执行/监督、插件脚本受控安装、凭据脱敏、FGS 策略、载荷自检、密钥生成。
- **app/**：GatewayService（specialUse FGS，Android 14+ 决策、启动自检、崩溃退避、用户停止不复活）+ Compose 仪表盘（状态头/网关启停/模型目录/流式聊天/诊断/日志）。
- 载荷：APK 内嵌 `libmagpie_exec.so`（Magpie CLI 0.1.3188）与 `libbun_exec.so`（Bun 1.4.2），经 nativeLibraryDir exec（P0-2 真机已验证）。
- 上游补丁草稿：patches/ 目录（M1-M5 Go 成文，待 PR）。

## 构建
- CI：推送到 main 触发构建，APK 自动发布到 Release v0.0.1-poc。
- 资产不入库：scripts/fetch-assets.sh 按官方 SHA256 下载校验。
- 本地构建：Android Studio 打开；需 JDK 17。

## P1/P2 真机验收清单
1. 下载 Release v0.0.1-poc 的 app-debug.apk（91.5MB 级，注意别用缓存的旧 74.7MB 版），覆盖安装。
2. 打开：Compose 卡片式仪表盘；状态头显示「就绪（网关未运行）」。
3. 点「启动网关」：出现常驻通知「Magpie 网关运行中」。
4. 点「停止网关」：通知消失。
5. 「一键诊断」：magpie 0.1.3188 / bun 1.4.2 exit=0（P0-2 已验证）。
6. 模型目录/聊天测试在网关未运行时报错属预期（真机联调阶段接核心后可用）。

## 已知边界（P2 进行中）
- 网关 key 本地生成并持久化（files/gateway-key.txt）；与核心引擎侧对齐（gateway-key add）在真机联调阶段做。
- 聊天模型从 /v1/models 动态选择（已实现）；首次使用需先点「刷新模型列表」。
- SSE 流式聊天已实现（增量渲染+自动降级非流式）。
- Claude 订阅不可用（上游无 Android 构建）；本地 ML 脱敏、RTK、命令型 MCP 暂缓。
