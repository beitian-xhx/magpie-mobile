## Magpie-Mobile 0.1.29-usage-summary (P6-3 功能对齐)

### 功能对齐原版 Magpie（54dc257f）
- **用量聚合概览（GET /api/usage）**:
  - overlay 白名单新增 `GET /api/usage`（原版 Usage 首屏聚合统计，`usageState(period, zone)`）并同步 manifest 哈希与白名单测试；
  - `CoreManagementClient.usageSummary(period)`：period 白名单（today/week/month/all），非法值客户端即拒；
  - 用量洞察卡片顶部新增“聚合概要（今日）”段，延续安全摘要解析；
  - UsageViewModel 注入 summaryReader 并同步四端点契约测试。