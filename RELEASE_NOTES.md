## Magpie-Mobile 0.1.26-insights-ui (P5-5 功能对齐)

### 功能对齐原版 Magpie（54dc257f）
- **用量洞察原生卡片（UsageInsightsCard）**:
  - 网关仪表盘新增“用量洞察”卡片，消费 /api/usage/heatmap 与 /api/usage/quotas 只读端点；
  - 弹性 JSON 摘要解析（InsightParser）：仅提取数字与条目计数，深度限制 2 层、上限 6 行，绝不展示请求正文或账号明细文本；
  - 读取失败独立于主请求状态（insightsError 单独呈现，不影响近期请求列表）；
  - CoreManagementClient 新增 usageInsight() 复用管理栈传输；UsageViewModel 注入 insightsReader 并新增成功/失败两条单测。