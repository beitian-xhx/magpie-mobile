## Magpie-Mobile 0.1.23-refactor-split (P5-2 结构优化)

### 结构优化（零行为变更）
- **NativeNavigation.kt 拆分（1206 → 314 行）**:
  - 网关接入/密钥/最近请求三卡 → GatewayCards.kt（287 行）；
  - 模型路由/Key 轮询两卡 → RoutingCards.kt（219 行）；
  - 系统稳定性/插件市场/MCP 三卡 → SystemCards.kt（219 行）；
  - 订阅插件/Chat Profile/模型目录三卡 → ChatCards.kt（246 行）；
  - 拆分为纯代码移动：全部 11 个卡片段经字节级比对与原文件一致，Dashboard 与辅助函数原样保留，括号平衡与 import 无重复；
  - 构建与测试由 GitHub Actions 全量回归验证。