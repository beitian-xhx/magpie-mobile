# P5 功能对齐缺口分析（原版 54dc257f vs 移动端）

> 依据：原版 `internal/gui/api.go`、`caller_keys.go`、`usage.go` 的 `mux.HandleFunc` 全表（经 GitHub contents API 固定 commit 提取），
> 对照 `patches/mobile/overlay/internal/gui/mobile_policy.go` 的精确白名单与 Kotlin `CoreManagementClient`/`UsageClient` 现有覆盖。

## 1. 移动白名单内端点覆盖（8/8 全部已对接）

| 端点 | Kotlin 客户端 | 状态 |
| :--- | :--- | :--- |
| GET /api/mobile/capabilities | CoreManagementClient.capabilities() | 已对接 |
| GET /api/state | CoreManagementClient.state() | 已对接 |
| GET /api/providers | ProviderManagementClient.getProviders() | 已对接 |
| GET /api/usage/requests | UsageClient.recentRequests() | 已对接 |
| GET /api/caller-keys | CoreManagementClient.listCallerKeys() | 已对接 |
| GET /api/caller-keys/models | CoreManagementClient.callerKeyModels() | **0.1.24 新增** |
| POST /api/caller-keys/add-key | CoreManagementClient.createCallerKey() | 已对接 |
| POST /api/caller-keys/copy-key | CoreManagementClient.copyCallerKey() | 已对接 |
| POST /api/mobile/providers/* | ProviderManagementClient.create/edit/discoverModels() | 已对接（overlay） |

## 2. 白名单外、可安全落地的只读候选（需 overlay 扩白名单 + Kotlin 客户端 + UI）
> **2026-11-10 P6 终审更新**：本表前四行已全部落地（heatmap/quotas/quotas-history 与 usage 聚合 → 0.1.27/0.1.29；
> notices → 0.1.28）。`GET /api/settings` 经源码裁定**不落地**：settingsState 组装分散于 api.go/join.go/gatewaymode.go
> 等多文件，快照含 LAN key、allowIPs、端口等敏感配置，仅“不渲染”无法缩小响应暴露面。`GET /api/usage/requests/content`
> 经源码裁定**不落地**：返回 agent 会话文件中的完整对话正文（requestContent），移动端聊天已有本地会话视图，
> 核心侧正文查看属低价值高风险，避免对话原文二次暴露面。

| 候选 | 上游端点 | 价值 | 安全评估 |
| :--- | :--- | :--- | :--- |
| **Usage 热力图** | GET /api/usage/heatmap | 用量分布可视化（原版统计页核心图） | 只读、无密钥、响应体需上限 |
| **额度配额视图** | GET /api/usage/quotas、/history | 订阅额度余量与历史趋势 | 只读；含账号名需走安全脱敏解析 |
| **用量汇总** | GET /api/usage | 聚合统计（原版 Usage 首屏） | 只读、聚合数据 |
| **请求详情** | GET /api/usage/requests/content | 单条请求正文查看 | 只读；正文可能含对话内容，须脱敏与上限 |
| **系统通知** | GET /api/notices | 核心侧提示条 | 只读、低风险 |
| **设置视图** | GET /api/settings | 只读展示当前配置（不含明文密钥字段） | 只读；POST /api/settings 系列暂不开放 |

## 3. 桌面专属 / 不可移植（明确不做，避免灰色入口）

- `GET /api/quit`、`POST /api/window/*`、`POST /api/set`、`POST /api/sync`：桌面窗口/生命周期控制；移动端生命周期由 Android Service 管理。
- `GET /api/omarchy`、`GET /api/cli`、`GET /api/agents/install`、`GET /boot.js`：桌面 CLI/Agent 安装器与引导脚本。
- `POST /api/settings/*`（约 45 个写端点）：桌面设置写入面；移动端设置走原生偏好，不直通核心写接口。
- `POST /api/usage/*-checkin`、`POST /api/usage/clear`、`POST /api/usage/codex-reset`：签到/清除/重置等有副作用操作，需逐项用户确认后另行评估。
- `GET /api/drift`、`GET /api/agents/*`：桌面 Agent 编排（Claude/Codex CLI 代理），移动端无对应运行时。

## 4. P5-4 选题（下一交付）

1. **Usage 热力图 + 额度视图**（GET /api/usage/heatmap、/quotas、/history）：overlay 白名单加 3 个 GET，`UsageClient` 扩展三个解析方法 + 单测，`RecentRequestsCard` 旁新增 UsageInsightsCard。
2. 遵循既有契约：精确路径白名单（非前缀）、`Cache-Control: no-store`、响应大小上限、密钥/账号名脱敏解析。