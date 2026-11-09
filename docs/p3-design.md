# P3 阶段：大部分 GUI 覆盖 Beta 设计与实施规划

## 1. 阶段定位与门槛（依据 plan.md §6）

**定位**：在 P2（可用原生 Alpha）具备审计、路由降级、多 Key 轮询与 Bun 运行时环境的基础上，演化为“大部分 GUI 覆盖”的完整 Beta 版。补齐多会话管理、Agent/Profile 参数配置、Library/HTTP MCP 工具观测以及插件生态发现能力。

**交付门槛**：
- 完成会话持久化与历史会话管理（Room 数据库支持多会话切换、分页流式加载与安全清除）；
- 支持 Profiles 与受控 Agent 参数配置（系统提示词、温度、Top-P、默认路由别名）；
- 支持 Library 与 HTTP MCP 工具契约观测（安全 schema 校验与状态呈现）；
- 持续遵循移动安全底线（W^X、零隐私泄露、严格输入校验）。

---

## 2. P3 核心里程碑与特性清单

### 【P3-1】多会话持久化与历史管理（Sessions & Conversation Store）
- **持久化架构**：Room 实体增加 `ChatSessionEntity` 与 `ChatMessageEntity`，支持多会话隔离；
- **状态与交互**：`ChatViewModel` 演化支持创建会话、切换活跃会话、重命名与删除会话；
- **UI 呈现**：在原生聊天页增加会话抽屉（Session Drawer）或会话列表入口。

### 【P3-2】Profiles 与受控 Agent 配置（Profiles & Agent Parameters）
- **参数控制**：支持为不同场景保存不同的 Profile（System Prompt、Temperature 0..2、Top-P 0..1、Max Tokens）；
- **绑定路由**：Profile 可关联特定模型别名（例如 `fast-chat`）或指定提供商；
- **UI 呈现**：在更多/聊天页增加 Profile 选择器与管理面板。

### 【P3-3】Library 与 HTTP MCP 工具观测（Library & HTTP MCP Tools）
- **MCP 协议契约**：定义受控 HTTP MCP 客户端元数据结构（工具名称、参数描述、端点 URL）；
- **安全沙箱**：仅允许受控 loopback 或经用户确认的 HTTPS MCP 工具端点；
- **UI 呈现**：在更多页增加 MCP 工具管理与健康观测卡片。

### 【P3-4】订阅插件扩展与自定义生态（Plugin Discovery & Ecosystem）
- **发现协议**：支持从受信任订阅源拉取插件元数据清单；
- **安全安装**：利用 P2-4 的 `SubscriptionPluginManager` 完成校验、沙箱安装与一键回滚。

---

## 3. P3 首个落地任务（P3-1：多会话持久化与历史管理）实施步骤
1. **数据实体与 DAO**：在 `core` 或 `app` 建立会话与消息的 Room 实体及 DAO 契约；
2. **测试驱动**：编写单测验证多会话创建、消息增量追加、按会话隔离读取与批量清理；
3. **ViewModel 整合**：将 `ChatViewModel` 与会话存储联动，支持自由切换活跃会话；
4. **UI 增强**：在 `ChatPanel` 顶部提供会话切换器与“新建会话”按钮。
