# Magpie-Mobile

把 [usemagpie.ai 的 Magpie](https://github.com/yetone/magpie)（AI 模型网关，MIT）移植为 Android 原生独立 APK：原生界面覆盖绝大部分可移植功能，提供后台模型网关、其他 App 接入与应用内聊天。完整范围见 [移植计划](plan.md)。

## 当前版本：0.1.30-upstream-3622（CI/真机验收分开）

- **上游核心**：v0.1.3622（commit `02512343`，从 v0.1.3188 基线升级，补丁冲突面为零）；
- **真机稳定性（P4/P5）**：网络热切换监听、电池优化白名单引导、低内存告警、冷启动遥测（<800ms 门槛）、崩溃事后脱敏审计；
- **功能对齐（P5/P6）**：历史会话重命名/删除、用量洞察四段卡片（聚合/热力图/额度/历史）、开源许可声明查看、密钥可见模型目录；
- **既有能力（P2/P3）**：多提供商安全接入、模型目录发现、别名路由与降级、Key 轮询池、Bun 订阅插件（回滚可逆）、HTTP MCP 工具、Chat Profiles、多会话持久化。

## 文档索引

| 文档 | 内容 |
| :--- | :--- |
| [docs/upgrade-guide-0.1.30.md](docs/upgrade-guide-0.1.30.md) | **安装与验证指南**（安装步骤/healthz 判定/新功能清单/故障排查） |
| [docs/p5-gap-analysis.md](docs/p5-gap-analysis.md) | 原版功能对齐缺口分析与终审裁定 |
| [docs/structure-review.md](docs/structure-review.md) | 代码结构评估矩阵 |
| [docs/delivery-p2/p3/p4.md](docs/) | P2/P3/P4 阶段交付报告 |
| [docs/session-storage.md](docs/session-storage.md) | 会话存储契约 |

## 隐私说明

- 所有凭据（提供商 Key、网关 Caller Key）只存于 App 私有目录（受 Android 文件加密保护）；**系统备份关闭**，仅支持 SAF 加密导出（schemaVersion 1，上限 5MB）；
- 网关仅监听 `127.0.0.1`；管理面 3430 与网关面 3425 凭据严格隔离，Key 不进入状态流/Intent/日志/剪贴板；
- 崩溃事后审计（`post_mortem.json`）强制经 SecretRedactor 脱敏并截断，绝无凭据落盘；
- 不申请额外权限：仅 INTERNET / 前台服务 / 通知，无位置/存储/联系人访问。

## 后台限制说明

- 网关以前台服务（`specialUse`）运行，常驻通知可随时停止；用户 Stop/ForceStop 后**不自动保活**；
- Android 14+ 的电池优化白名单需用户手动授权（App 内一键引导），否则 Doze 可能中断长耗时推理；
- 网络切换（Wi-Fi ↔ 蜂窝）由 `NetworkConnectivityMonitor` 实时感知并在 UI 呈现；低内存触发 `TRIM_MEMORY_RUNNING_CRITICAL` 告警。

## 安全边界

继承真实 mobile 核心 M1/M2/M3/M5 与核心签发 Key。管理 3430 Cookie 与网关 3425 Bearer 隔离；同 UID 插件不是沙箱。移动管理面是**精确方法+路径白名单**（非前缀匹配），每项能力单独审查后开放；新增/编辑只支持基础 custom API-key OpenAI Chat，安全 canonical/HMAC revision/严格锁/原子保存。

## 验证与操作

CI 执行 Python 应用器、JVM 全量测试、Go 安全测试、host fake-vendor GET-only 烟测，再构建固定 v0.1.3622/Go1.26.8 mobile,nogui AndroidARM64 PIE 核心 `0.1.3622-mobile.1`（16KB 页对齐，`-Wl,-z,max-page-size=16384`）与固定签名 APK，核对载荷 SHA-256 与签名证书。安装后核对版本/SHA 并按 [安装指南](docs/upgrade-guide-0.1.30.md) 验证。

本地大版本升级基线：v0.1.3622 起步（旧基线 v0.1.3188 的 24 个补丁目标文件零变更，升级流程见技能库与 gap-analysis §5）。
