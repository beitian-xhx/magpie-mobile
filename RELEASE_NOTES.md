## Magpie-Mobile 0.1.27-quotas-history (P6-1 功能对齐)

### 功能对齐原版 Magpie（54dc257f）
- **额度历史洞察接入（Quotas History）**:
  - `loadInsights()` 扩展拉取 `/api/usage/quotas/history`（此前仅 heatmap 与 quotas 两端点）；
  - 用量洞察卡片新增“额度历史概要”段，延续安全摘要解析（仅数字与条目计数）；
  - UsageViewModelTest 同步三端点契约断言（3 次调用与三段 JSON 存储）。