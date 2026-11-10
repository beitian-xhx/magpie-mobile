# Magpie-Mobile GUI 功能覆盖表（v0.1.31 态）

对照原版 Magpie GUI（v0.1.3622）功能域与移动端原生实现，plan.md §8 "GUI 功能覆盖表"交付物。
两个分母口径：**原版桌面功能全集** 与 **plan.md §1 确认可移植范围**；不可用项不计完成。

## 1. 原生页面与卡片清单（16 个 UI 面，全部已实现）

| 页面 | 组件 | 功能 |
| :--- | :--- | :--- |
| 网关（首页） | Dashboard | 状态头/启停/新功能验证清单入口 |
| 网关 | GatewayEndpointCard | 端点展示、Base URL/API Key/cURL 一键复制 |
| 网关 | GatewayKeysCard | Caller Keys 列表/新增/复制 |
| 网关 | RecentRequestsCard | 近期请求审计 |
| 网关 | UsageInsightsCard | 用量聚合/热力图/额度/历史四段概要 |
| 网关 | ModelRoutingCard | 模型别名路由与降级规则 |
| 网关 | KeyPoolingCard | 提供商 Key 轮询池管理 |
| 网关 | SystemStabilityCard | 网络/电池/内存稳定性 |
| 网关 | PluginMarketCard | 受控插件市场发现与安装 |
| 网关 | McpServerCard | HTTP MCP 服务管理 |
| 更多 | SubscriptionPluginCard | Bun 订阅插件管理/回滚 |
| 更多 | ChatProfileCard | Agent 预设（提示词/温度/Top-P） |
| 更多 | LicenseNoticesCard | 开源许可声明（/api/notices） |
| 更多 | 版本与诊断卡 | 版本/冷启动耗时/崩溃审计/一键诊断 |
| 提供商 | ProvidersPanel | 提供商 CRUD/目录发现/Key 池/编辑器 |
| 聊天 | ChatPanel | 多会话/流式对话/历史管理/Profile/模型选择 |

## 2. 原版功能域覆盖对照（依据 docs/p5-gap-analysis.md 终审）

| 原版功能域 | 移动端状态 | 说明 |
| :--- | :--- | :--- |
| 提供商管理（CRUD/发现） | ✅ 已实现 | /api/mobile/providers/* 三端点 |
| 网关密钥（Caller Keys） | ✅ 已实现 | 列表/新增/复制/模型目录 |
| 用量统计（Requests/聚合/热力图/额度/历史） | ✅ 已实现 | 6 个只读端点 |
| 核心状态与能力协商 | ✅ 已实现 | /api/state + capabilities |
| 开源许可声明 | ✅ 已实现 | /api/notices |
| 会话管理（原生） | ✅ 已实现 | Room 多会话+重命名/删除（超出原版桌面网页的移动原生能力） |
| Chat Profiles | ✅ 已实现 | 系统提示词/参数/绑定路由 |
| HTTP MCP 工具 | ✅ 已实现 | 受控 loopback/HTTPS |
| 订阅插件（Bun） | ✅ 已实现 | 受控清单+回滚 |
| 设置写入面（约 45 个 POST） | ⛔ 裁定不落地 | 桌面专属写面；移动端原生偏好替代（gap-analysis §2 终审） |
| 请求正文查看（requests/content） | ⛔ 裁定不落地 | 对话正文二次暴露面，低价值高风险 |
| 桌面窗口/CLI Agent/签到类 | ⛔ 不适用 | 桌面专属运行时 |

## 3. 覆盖结论

- **确认可移植范围内：100% 通过**（plan.md §7 目标 ≥90%）；
- 不可用项全部有清晰原因（桌面专属/安全裁定），无"灰色按钮充当完成"；
- 关键链路（提供商/模型/路由/网关/统计/备份/聊天）全部通过 CI 回归 + 契约测试。