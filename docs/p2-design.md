# P2 阶段：可用原生 Alpha 设计与实施规划

## 1. 阶段定位与门槛（依据 plan.md §6）

**定位**：在 P1（宿主、安全和数据基础）全面封包的基础上，演进为具备真实请求审计、模型多渠道路由容灾与多账号池化调度的原生 Alpha 网关。

**交付门槛**：
- 仪表盘与原生界面具备完整的请求审计能力（时间、模型、状态、延迟与 Token 用量，严格脱敏正文）；
- 至少支持一组核心模型的备用回退（Fallback）与别名路由机制；
- 至少支持一个多 Key 账号池轮询渠道；
- 在 ARM64 具备 Bun 运行时的环境下，验证插件生命周期与能力协商契约。

---

## 2. P2 核心里程碑与特性清单

### 【P2-1】请求审计与用量历史（Usage & Recent Requests）
- **核心策略补丁（M6）**：在 `patches/mobile/overlay/internal/gui/mobile_policy.go` 开放只读白名单 `GET /api/usage/requests`（支持 limit 过滤，严禁写操作与路径穿越）；
- **客户端与状态层**：激活 `UsageClient`，提供带并发防抖与缓存的 `UsageViewModel`；
- **UI 呈现**：在网关概览页新增「近期请求速览（Recent Requests）」卡片（展示最近 5 条：请求时间、模型、耗时 ms、HTTP 状态、Tokens），并提供独立刷新能力；
- **隐私保护底线**：遵循 plan.md §4.5，零正文上报、零明文密钥，仅记录指标元数据。

### 【P2-2】基础路由组与模型别名（Routing & Fallback Groups）
- **路由规则映射**：支持自定义模型别名（如将客户端请求的 `gpt-4o` 自动别名路由至特定提供商或性价比模型）；
- **容灾降级链（Fallback）**：主渠道遇 429/超时/不可用时，网关底层平滑尝试备用渠道；
- **原生路由管理界面**：在提供商或更多页面提供原生路由组配置入口。

### 【P2-3】提供商多 Key 轮询与账号池（Key Pooling & Load Balancing）
- 单提供商支持配置多个 API Key；
- 轮询（Round-robin）调度与失败隔离机制；
- 账号状态监控与只读配额展示。

### 【P2-4】订阅插件协议与运行时环境对接（Subscription Plugins & Bun Runtime）
- 审查并封装 APK 预置 `libbun_exec.so` 在 Android 沙箱内的执行权限与环境变量；
- 提供移动能力协商声明，建立插件安全沙箱与版本回滚机制。

---

## 3. P2 第一项落地任务（P2-1：近期请求与用量审计）实施步骤

1. **移动策略（Go 侧）**：在 `mobile_policy.go` 允许 `GET /api/usage/requests`；
2. **移动策略单测**：更新 `mobile_policy_test.go` 验证该端点允许 GET 并拒绝非法 Method 及 Path；
3. **Core 层数据流**：编写 DTO 解析近期请求 JSON 结构，并覆盖单元测试；
4. **App 层架构**：实现 `UsageViewModel` 并联动网关运行状态进行生命周期监听；
5. **UI 卡片集成**：在 `AppPage.GATEWAY` 增加「近期请求与用量」卡片。
