## Magpie-Mobile 0.1.28-license-notices (P6-2 功能对齐)

### 功能对齐原版 Magpie（54dc257f）
- **开源许可声明查看（GET /api/notices）**:
  - overlay 移动白名单新增 `GET /api/notices`（原版“设置 › 关于”的开源许可数据源）并同步 manifest 哈希与白名单测试；
  - `CoreManagementClient.notices()` 解析 `{"text": ...}` 契约；
  - 更多页新增“开源许可声明”卡（NoticesViewModel + LicenseNoticesCard）：按需加载、64KB 显示上限、可滚动查看；
  - CoreManagementClientTest 新增许可文本契约测试。