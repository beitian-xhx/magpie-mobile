## Magpie-Mobile 0.1.24-key-models (P5-3 功能对齐)

### 功能对齐原版 Magpie（54dc257f）
- **网关密钥可见模型目录（/api/caller-keys/models）**:
  - 新增 `CoreManagementClient.callerKeyModels()`，对接原版 `caller_keys.go` 的 keyModelJSON 契约（id/name/provider/providerName/group/account/key/plan）；
  - 该端点是原版 GUI 模型选择器（含路由组 group/<id>、账号条目与决策模型）的数据源，移动端此前仅用 /v1/models，缺少按密钥可见性与路由组语义；
  - 纯元数据接口，无任何密钥明文；复用既有 BoundedCoreHttp 传输与 webGuard cookie 安全栈；
  - CoreManagementClientTest 新增解析与请求契约测试（路由组/提供商/账号三类条目）。