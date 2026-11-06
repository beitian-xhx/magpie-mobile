# P1 预研：网关仪表盘设计与最小移动补丁清单

状态：设计稿（非实现）。P0-2 真机验证通过后才进入实现；本文件供 P1 启动时直接评审。

## 1. 首个原生页面：网关仪表盘

### 信息架构（单屏）
- **状态头**：网关运行状态（运行中/已停止/启动中）、监听地址（127.0.0.1:3425）、LAN 开关状态
- **快捷操作**：启动/停止网关（写 UI 偏好+服务意图）、一键复制监听地址
- **模型速览**：接入的提供商数量、可用模型计数、当前默认模型（GET /api/state）
- **近期请求**：最近 5 条请求（模型/供应商/状态/耗时，GET /api/usage/requests?limit=5）
- **健康诊断入口**：跳转 PoC 的诊断页（保留 P0 能力）

### 数据流（严格单向）
- UI 事件 → ViewModel → EngineController（服务绑定）
- 服务持有 CLI 进程；CoreClient（loopback:3430，管理 cookie 鉴权）拉取 /api/state、/api/usage
- DataStore 仅存 UI 偏好与启动意图；**不缓存核心状态**（避免双源冲突，plan.md §4.4）
- 断连/超时：显示离线横幅 + 重试按钮；不自动重放写操作

### 布局要点
- Compose Material 3，深浅色跟随系统；中文为主
- 状态头常驻；列表分页（limit=5 起步，更多入口进 Usage 页）
- 无障碍：按钮文字语义完整，状态色不只靠颜色区分（附文字标签）

## 2. 最小移动补丁清单（Magpie 源码侧，维持最小 fork）

按 plan.md §4.2/§4.3，P1 需要上游配合的最小改动（每项独立 PR 粒度）：

| # | 补丁 | 目的 | 风险 |
|---|---|---|---|
| M1 | 移动构建 tag 下关闭 heap/cpu/goroutine/restart 等 loopback 调试入口 | 攻击面收缩 | 低；桌面默认不变 |
| M2 | loopback 客户端强制有效 gateway key（可选开关，移动 tag 默认开） | 其他 App 无 key 不能消费网关 | 中；需测试本地 agent 兼容性 |
| M3 | 管理服务绑定 127.0.0.1 显式化 + 移动 tag 拒绝 --web 非 loopback | 管理面不向 LAN 开放 | 低 |
| M4 | 备份/导出接口接受「内部暂存路径」参数（不透明 ID），输出仍走核心加密 | SAF 桥接不暴露绝对路径 | 低 |
| M5 | GET /api/mobile/capabilities（schemaVersion、版本/提交、功能开关） | 能力协商，避免 UI 猜 | 低 |

非目标（P1 不做）：桌面功能裁剪、CLI JSON 化改造、订阅渠道新增。

## 3. P1 工程骨架（预先冻结接口，避免返工）

- app/ 主工程（Compose）；core/ 纯 Kotlin 模块：CoreClient + DTO（以固定源码 api.go 为契约）
- 引擎载荷沿用 P0-2 验证过的 libmagpie_exec.so / libbun_exec.so 打包与 exec 方式，不改动
- 服务层：GatewayService（Android 10-13 普通 FGS；Android 14+ specialUse FGS，PLAN.md §4.6）
- 测试：CoreClient 对 mock 核心的契约测试；服务生命周期用 instrumented test（真机跑）

## 4. 决策待确认（进 P1 时提问确认，不预先默认）

1. P1 里程碑验收里，LAN 网关是否必须支持（还是 P2 再开）——影响 M2 的测试矩阵
2. 应用包名 cc.fplj.magpiemobile 是否沿用到正式版（改名涉及数据迁移）
