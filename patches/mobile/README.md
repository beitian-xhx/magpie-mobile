# 固定 Magpie mobile 安全补丁

基线：54dc257f100e2ca82a843b1eea6a24b8006781f5；Go 1.26.8。
这是实际源码精确替换 + overlay，不是示意 diff。非 mobile 构建保持原桌面行为；mobile 常量包没有 gateway/gui 依赖，避免循环。

## 应用器

在 Magpie-Mobile 仓库调用：

```sh
python3 scripts/apply-mobile-core.py --source-root /path/to/pinned-magpie --check
python3 scripts/apply-mobile-core.py --source-root /path/to/pinned-magpie
```

- --source-root 必填：固定 Magpie Git checkout 或源码归档根目录。
- --patch-root 可选：受信任补丁包根目录，默认本仓库 patches/mobile；不是不受信任外部补丁入口。
- --check：只核对，不写入，不下载依赖，不执行 Go 或构建工具。
- Git checkout 核对 HEAD；归档不能证明整个 commit，只通过 go.mod、八个目标文件的 SHA256 固定相关源码。
- 原文件 hash、每条 old 文字唯一匹配、结果 hash、overlay 完整清单/hash 全部先通过才写入。拒绝偏移、模糊匹配、重复应用和混合状态。
- 每个文件原子替换；I/O 失败回滚已完成的文件。源码目录必须为独占工作树，不支持并发写入或文件系统突然换挂载；失败后重新检查，不自动套另一通道。

## 实际安全边界

- M1：mobile 不注册 ClaudeRuns、heap、cpu、goroutines、restart。dumpFirst 在 mobile 直接返回 next，同时关闭 Handler 与 Front 的外层旁路。
- M2：loopback、IPv6、代理/page-port、LAN、嵌入 Handler 都需要有效 access.Authenticate(key)。拒绝 magpie、magpie-alma、空值、错值、撤销/失效值；无 callback、preflight、image token、OIDC、prove 或 key-page 的无 key 例外。
- OIDC/prove/key-page 路由在 mobile 不注册。原 Caller Identity、凭据 header 脱敏、read-only、模型/预算限额链保留。内部 request context marker 仅记住本请求完成鉴权后的状态，避免 header 脱敏后的二次重验；不来自 HTTP header。
- M3：StartWeb 在创建 key、listener 和 backend 前拒绝非 literal-loopback 地址。允许 127.0.0.0/8 与 ::1；拒绝 localhost 等 DNS 名称（App 使用 127.0.0.1）。网关的 LAN 监听未在本补丁禁用，但任何来源仍必须有效 gateway key。
- 原生 updater.start 在 mobile 第一条业务语句前 return，不 place、不注册 restarter、不启动 goroutine；StartWeb 也不调用它。APK 自身更新归 App 管理。
- App 必须注入 MAGPIE_WEB_KEY，通过原 CheckWebKey（至少 16 字符，ASCII 字母/数字/-._~）。mobile 读取后 Unsetenv，同时清除 reexec key，不随机生成替代管理凭据；w.Link 不包含 key。无新匿名 bootstrap。Cookie 沿原 webGuard，mobile 使用进程会话 cookie。

CLI 运行时兼容：mobile 的 printWeb 在旧桌面输出之前，仅打印 w.Addr 和 App 私有鉴权说明，然后 return；不读取 w.Link、不切片 /?k=、不输出 key/proxy/network link，也不保留“link carries key”提示。非 mobile 继续原函数体。新增 CLI 移动回归覆盖 keyless Link 不 panic 及 key-bearing Link 不泄密；这些 Go 回归由 Actions 执行，不能仅用编译替代运行验证。现有49个缓存Go文件扫描只发现 printWeb 同一切片的两个源码副本，没有其他 Index/Link 消费点；该扫描不冒充完整上游仓库审计。

## 管理 API（原 webGuard 与 webOwnPage/CORS 保持）

所有下列 API 使用 Cookie: magpie_web_<实际管理端口>=<App 注入的管理 key>，不是网关推理 key。

| 方法 | 路径 |
| --- | --- |
| GET | /api/mobile/capabilities |
| GET | /api/caller-keys |
| GET | /api/caller-keys/models |
| GET | /api/state |
| GET | /api/providers |
| POST | /api/caller-keys/add-key |
| POST | /api/caller-keys/copy-key |
| POST | /api/mobile/providers/create |
| GET | /api/mobile/providers/editable/{id} |
| POST | /api/mobile/providers/edit |

copy-key 是共享 task-1 revision 3 确认的新增恢复白名单；客户端仅用于本 App 唯一识别的自有 key。完整 secret 只能内存→App 私有文件，不进 UI、日志、剪贴板或导出。add-key 请求 {"name":"..."}；copy-key 请求 {"key":"<core-issued-id>"}；原核心回包 {"keys": [...], "accountNames": {...}, "secret": "..."}。GET keys 只有 masked，不含完整 secret；新 key 格式 sk-magpie-key- + 48 hex，由 core 随机签发，不由 App 猜造。

能力 JSON 固定为：

```json
{"schemaVersion":1,"coreVersion":"<Version>","mobile":true,"loopbackKeys":true,"debugEndpoints":false,"adminLoopbackOnly":true,"nativeAutoUpdate":false,"providerSchemaVersion":1,"providerManagement":true}
```

Provider仅新增受控创建、canonical快照与revision限定编辑，详细字段与保留/锁限制见[Provider协议](<PROVIDER_API.md>)；不开放原generic provider/save。

其余 /api 精确方法/路径均 404，包括 HEAD/OPTIONS、unknown、desktop execution、update/restart、backup、provider save/test 和 key rotate/off/on/remove。无 cookie/错误 cookie 先由原 webGuard 拒绝；外站 Origin/Fetch-Site 仍由原 CORS 层拒绝；因此这些请求可先返回 401/403，而不是绕过鉴权强求 404。非规范路径与 RawPath 编码也拒绝，不能通过 ServeMux 自动重定向钻白名单。

## 验证与已知风险

本地仅运行 Python 应用器单测、源码哈希与精确匹配静态核对；未本地 Go 编译、Go test 或模型推理。TestMobile* 编译/执行结果由 Actions 验证，不能用静态检查代替。本机没有 gofmt，因此未声称已完成 Go parser/formatter 验证。

建议 Actions 在独占固定 checkout 应用后执行（由 Lead 维护 CI）：

```sh
go test -tags nogui,mobile ./internal/gateway ./internal/gui ./internal/cli ./internal/provider -run '^TestMobile' -count=1
GOOS=android GOARCH=arm64 CGO_ENABLED=0 go build -buildmode=pie -tags nogui,mobile -o magpie-android-arm64 .
```

网关预算回归仅使用上游测试套件的 local httptest fake，无真实提供商/模型调用或额度支出。其余移动回归使用语义路由、内存 handler 和隔离 key 存储。

兼容性边界：匿名 ServedBy/ServedBusy HTTP 探活在 mobile 返回 unknown，不能为其放开匿名 root/统计 API。启动后同进程 GUI 使用 served 内部状态；占用探测另有 TCP Taken，App readiness 是双 TCP，不需要匿名 200。旧 desktop helpers（例如只有 callback/image token 的客户端）必须改为真正 key 或在 mobile 禁用；本补丁不支持跨进程旧网关自动识别/迁移，不承诺订阅插件无需改造即可运行。providers/state 原上游读取可能触发元数据刷新；当前白名单不允许付费测试或未经受控canonical事务的配置写入。
