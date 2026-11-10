## Magpie-Mobile 0.1.25-usage-insights (P5-4 功能对齐)

### 功能对齐原版 Magpie（54dc257f）
- **用量洞察端点接入（Usage Insights）**:
  - overlay 移动白名单新增 3 个只读 GET：`/api/usage/heatmap`（用量热力图）、`/api/usage/quotas`（额度配额）、`/api/usage/quotas/history`（额度历史），同步补齐 overlay 白名单测试用例；
  - `UsageClient.usageInsight()` 白名单化端点枚举（精确路径非前缀），管理 cookie 鉴权、响应 2MB 硬上限、弹性解析原始 JSON；
  - UsageClientTest 新增 3 条契约测试（路径/cookie、缺 cookie 快速失败、错误码传播）；
  - 全量缺口分析归档 `docs/p5-gap-analysis.md`：原版 120+ 端点三档分类（已对接/可落地/桌面专属），明确不做桌面窗口、CLI Agent、签到与设置写面。