# Magpie-Mobile（开发验证版）

目标与范围见 [移植计划](plan.md)。不是主要GUI功能完整覆盖版。用户已确认0.1.1在当前手机启停正常；0.1.2安全接入有CI/host证据，不扩展为真实渠道、多系统或后台稳定验收。

## 当前里程碑：0.1.3-providers

- Compose 原生提供商列表、筛选、API Key 新增、模型ID输入/勾选、读取并安全编辑。
- 初版新增/编辑限定自定义 API-key OpenAI Chat，可用 DeepSeek/OpenAI 地址模板；模板不是订阅登录或真实推理验收。
- 管理客户端只用3430专用Cookie；网关仍用3425核心注册Key，管理凭据不进入状态流、Intent、日志或WebView。
- 创建DTO与编辑DTO分离。编辑必须先取得核心新快照+opaque revision，只提交dirty字段；版本冲突拒绝覆盖，不静默重读重写。
- 空编辑Key保留原值；主动清空模型才禁用全部，Name-only不能把原implicitAll变成noModels。
- 核心从最新canonical配置事务中开始，受控原子写并保留未支持字段/注释/已有凭据。异常文件、重复id、失效revision、未知字段等拒绝。
- 提供商文件读异常不是正常空列表；暂停写入。POST结果未知后清输入Key，禁止同draft重放，先刷新真实核心状态。
- Key仅留短暂密码表单/请求与App私有核心配置，不展示完整保存Key；不rememberSaveable，不自动贴剪贴板。
- 外部地址须HTTPS；本机HTTP仅由用户明确允许literal loopback。该页面不自动test/detect或调用模型。

## 受控接口

见 [提供商客户端](core/src/main/kotlin/cc/fplj/magpiemobile/core/ProviderManagementClient.kt) 与 [精确移动补丁](patches/mobile/manifest.json)。

- GET /api/providers：现有列表，key为安全metadata，models对象数组与chosen原始ID分开。
- POST /api/mobile/providers/create：只允许基础创建字段。
- GET /api/mobile/providers/editable/{id}：不含完整Key的canonical编辑快照与revision。
- POST /api/mobile/providers/edit：限定dirty字段，不能通过原任意provider/save破坏配置。
- provider/test、detect、删除、订阅/桌面执行及其他未验收操作仍不开放。

## 构建与验证

固定Magpie commit54dc257f/Go1.26.8，mobile,nogui Android ARM64 PIE；Bun固定1.4.2并校验官方ZIP。Actions跑Python应用器、Kotlin/App JVM、Go安全/提供商事务和真实host核心烟测，最后核对APK原生CLI与构建产物SHA256。
当前新增测试仅编写时不算通过；以对应提交的Actions与发布产物为准。host烟测使用synthetic Key与保留域名，不做真实推理。

## 手机操作

1. 安装对应0.1.3独立Release，核对版本/SHA；若签名冲突先回报，保留旧应用和数据。
2. 启动并完成“核心安全连接”，再刷新“提供商管理”列表。
3. 新增自己的可信API地址/Key和账号可用的原始模型ID；保存前会二次确认，不自动推理。
4. 编辑先读取新快照；Key留空保持，未触碰的模型和高级设置保持。冲突或未知结果先重读，不重复点击保存。
5. 保存后刷新网关模型目录。凭据已配置、模型已列出、保存已确认，都不等于真实推理已通过。

## 后续边界

真实API/订阅测试需额外用户授权与预算。多协议/订阅编辑、M4加密SAF桥、数据库、高级路由、已验证插件、后台多系统16KB、完整许可义务仍按计划推进。同UID插件不是沙箱。
