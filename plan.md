# Magpie-Mobile 深度调研与 Android 原生移植计划

## 1. 目标、结论与已确认范围

**目标：开发在手机本地运行的独立 Android APK，以原生界面覆盖 Magpie GUI 的绝大部分可移植功能，并提供后台模型网关、其他 App/局域网接入和应用内聊天测试。**不是远程控制端，不是 Termux 启动器，也不是把原版网页套壳。

调研结论：**有可复用的 Android CLI 和管理后端，技术路线成立；但独立 APK 的运行时兼容性尚未验证，必须先做原生运行时 PoC。**主要工作在 Android 生命周期、安全边界和原生界面重建，而非重新实现模型网关。

已确认的取舍（提问工具逐项确认）：
- 软件为 usemagpie.ai 的 Magpie AI 网关；首版自用/侧载，暂不上 Google Play。
- Android 10+、ARM64；不依赖 root、Termux 或 Ubuntu 容器。允许 CLI + 本地管理接口混合。
- 主要 GUI 管理、订阅接入、后台网关和聊天测试均纳入目标；无法合法稳定移植的订阅明确标注、后续补齐，不冒充可用。
- 接受受控配置、文件导入导出和连接教程，替代越权读写其他 App 私有目录。
- 大型本地脱敏模型、RTK、不兼容命令型 MCP 后续按需加入；首版实现规则脱敏及相关管理。
- 插件提供已验证列表；其他插件须风险确认和兼容性检查，固定版本、默认手动更新。仅插件自定义 HTML 页面可用隔离 WebView，核心页面均为原生。
- 接受核心凭据存于 App 私有目录并受 Android 文件加密保护；禁用系统备份，仅允许加密导出，不宣称所有核心凭据已单独由 Keystore 加密。
- 如果 Bun 只在较新系统通过验证，保留 Android 10+ 核心支持，低版本明确标注插件不可用；如果所有目标系统均失败，停止插件运行路线并重新确认方案。

本轮仅查阅官网、完整参考手册、固定源码、插件源码和 Android 官方资料；未安装、编译、登录或执行 Magpie，也未修改工作区。以下性能和兼容性是实施验收目标，不是已实测结果。

## 2. 调研基线与关键发现

### 2.1 固定版本，避免追逐不断变化的最新版

基线为 **v0.1.3188 / commit `54dc257f100e2ca82a843b1eea6a24b8006781f5`**。调研期间官网从 3186 更新至 3188，说明版本变化快。实施锁定源码、Go 工具链、Bun、插件版本与 SHA-256，不运行自动追最新。

证据：[Go 模块版本元数据](https://proxy.golang.org/github.com/yetone/magpie/@latest)、[官方发行元数据](https://usemagpie.ai/api/latest)、[固定源码](https://github.com/yetone/magpie/tree/54dc257f100e2ca82a843b1eea6a24b8006781f5)。其中官方 Android ARM64 CLI 资产约 **70.9 MB**；这不是最终 APK 大小，加入 Bun、界面和许可证后还会增加。

### 2.2 核心已有 Android 支持，但 GUI 不能直接搬过去

固定版本的构建规则包含 `CGO_ENABLED=0 GOOS=android GOARCH=arm64 -tags nogui`。桌面使用 Wails/系统 WebView 及 HTML，Android 终端版支持 CLI/TUI/Web，但不包含 Android 原生 GUI。应复用 Go 网关和 JSON 管理接口，重建 Kotlin/Compose 界面。

证据：[构建规则](https://github.com/yetone/magpie/blob/54dc257f100e2ca82a843b1eea6a24b8006781f5/Makefile)、[管理 API 实现](https://github.com/yetone/magpie/blob/54dc257f100e2ca82a843b1eea6a24b8006781f5/internal/gui/api.go)。

### 2.3 CLI 功能广，但不是所有命令都有 JSON 输出

已核验结构化输出包括 settings list/get、accounts、quota、sessions、tool 调用及 gateway-key issue 等。固定源码中的 providers/groups 展示与 usage 等不能统一假设支持 `--json`，usage 有文本/CSV 输出。

因此：**CLI 用于启动核心和少量已有结构化操作；复杂管理走本地 API。**不解析彩色终端表格、不模拟 TUI、不用 shell 拼接用户输入。

证据：[CLI 实现目录](https://github.com/yetone/magpie/tree/54dc257f100e2ca82a843b1eea6a24b8006781f5/internal/cli)。

### 2.4 Bun 已有 Android 分支，Claude 订阅仍是另一道门槛

Magpie 的 Bun 宿主使用 **Bun 1.4.2**，已有 Android ARM64 目标与校验值，允许通过 `MAGPIE_BUN` 指定路径。MoeJS 是纯 Go 中间件引擎，缺少完整 Node API，不能替代订阅插件所需的 Bun。

但 Bun 正式 Android ELF 的最低 API、动态链接器、依赖库、是否依赖 Termux 路径及 16 KB 页兼容性仍未验证。

**Claude 订阅依赖实际 Claude Code 进程，而固定源码明确没有 Android 构建。**只做 OAuth 不能替代这条执行链。首版标注 Claude 订阅不可用；Anthropic API Key 是独立可用渠道，不称为订阅支持。

证据：[Bun 平台选择](https://github.com/yetone/magpie/blob/54dc257f100e2ca82a843b1eea6a24b8006781f5/internal/plugin/bun.go)、[Claude Code 平台选择](https://github.com/yetone/magpie/blob/54dc257f100e2ca82a843b1eea6a24b8006781f5/internal/claudecode/claudecode.go)、[Claude 订阅桥接](https://github.com/yetone/magpie/blob/54dc257f100e2ca82a843b1eea6a24b8006781f5/docs/subsystems/claude-subscription-bridge.md)。

### 2.5 ZCode 有现实适配路径，但不能承诺所有套餐长期有效

调研到的 ZCode 插件版本为 0.1.51，使用浏览器授权 + 服务端 init/poll，并非必须启动本机回调服务器，适合验证 Custom Tabs 授权。不能读取其他 App 的 ZCode 私有登录文件，也不能劫持 `zcode://` 回调。

优先验证浏览器授权、轮询、模型列表和真实推理；服务商风控、套餐限制、不能刷新令牌等应原样报告并提示重新登录，不绕过。GLM Coding Plan API Key 和 ZCode 浏览器订阅分别验收。插件实施前另行锁定准确提交与资产校验值。

证据：[ZCode 插件源码](https://github.com/magpie-community/plugins/blob/main/packages/zcode/index.mjs)、[插件说明](https://github.com/magpie-community/plugins/blob/main/packages/zcode/README.md)。其他 ChatGPT、Copilot、Gemini 等渠道也必须逐项验证，不能由桌面支持推导为 APK 已支持。

### 2.6 文档与源码存在差异，必须以固定实现为准

- 新版备份已支持显式包含订阅/插件登录；默认不包含。手机导出、导入、自动备份均显式设置 `SignIns=false`，避免导入接口省略参数时默认恢复登录。需要搬登录时单独确认，且迁移不保证凭据仍有效。
- gateway mode 的界面隐藏不等于后端没有能力；固定实现仍有 Sessions/MCP 路由。不能用旧 CLI 帮助文字缩减功能清单。
- 核心有提供商小请求测试和工具调用，但没有核验到通用多轮 Chat/Playground 主页面。**应用内聊天测试是新增原生功能**，不是原版聊天页的直接移植。

证据：[备份实现](https://github.com/yetone/magpie/blob/54dc257f100e2ca82a843b1eea6a24b8006781f5/internal/gui/backup.go)、[gateway mode](https://github.com/yetone/magpie/blob/54dc257f100e2ca82a843b1eea6a24b8006781f5/internal/gui/gatewaymode.go)、[提供商测试](https://github.com/yetone/magpie/blob/54dc257f100e2ca82a843b1eea6a24b8006781f5/internal/provider/test.go)。

## 3. GUI 功能覆盖矩阵

“主要功能”按实际操作清单验收，而不是按页面数量或截图相似度判断。

| 子系统 | 上游 GUI 的主要能力 | 手机端交付与边界 |
|---|---|---|
| 提供商 | 预设/自定义地址、API协议、多个 Key/账号、测试、余额、排序、代理、并发限制 | 原生重建；保留已有校验与核心行为 |
| 模型 | 动态目录、可见性、别名、能力、价格、推理档位、API覆盖 | 原生搜索/编辑/刷新；价格未知显示未知，不当作免费 |
| 订阅 | 登录/取消/退出、多账号、刷新、额度与部分签到操作 | 优先验证 ZCode 及其他移动可用渠道；Claude 明确待支持；收费/额度消耗操作确认后执行 |
| 路由 | 路由组、备用模型、账号分配、权重/顺序/智能等策略、粘性、条件规则、嵌套组 | 复用核心算法，重建编辑器及实时历史；测试循环/嵌套限制、部分流失败 |
| 网关 | 启停、监听、LAN、CORS/IP限制、密钥、模型/账号权限、预算、临时密钥等 | 原生控制；所有客户端含 loopback 均鉴权，管理端不向 LAN 开放 |
| Agent/Profiles | 模型/effort/tier/可见列表、配置预览、连接/断开还原、方案切换 | 保留受控模板及导入导出；不能自动改其他 App 私有配置，不伪造“已安装” |
| Usage/额度 | 请求过滤、token/费用/cache/速度/TTFT、CSV、额度/余额历史 | 原生图表与分页；支持导出；供应商估算与实际账单区别标注 |
| Sessions | 列表、转录、Markdown/导出、恢复命令、迁移、回收站 | 管理本 App 聊天与网关已有记录、用户导入记录；桌面终端动作改复制/分享，不假称可恢复网关会话 |
| Library | **指令、MCP服务器、技能、Hooks、子代理、RTK 六类**，编辑、分配、导入、备份 | 六类管理均保留于受控工作区；配置存在不等于程序可执行；冲突提示、不覆盖用户文件 |
| MCP/工具 | HTTP/SSE/stdio配置、OAuth、工具开关、MCP路由、图像/视觉/搜索等工具 | HTTP 与可验证协议优先；stdio 仅允许已验证内置程序；媒体/工具按模型能力展示 |
| 插件 | 发现、npm/git/local导入、开关、移除、版本/更新、登录、配置、自定义页 | 已验证目录+显式风险确认；Android 兼容检测；自定义页隔离 WebView；不兼容依赖标明原因 |
| 隐私/观测 | 规则及自定义脱敏、提醒/拦截、试用、记录、OTLP、本地模型辅助 | 首版规则与记录/观测；本地 ML 后续；明确图片数据等不被规则脱敏的范围 |
| 设置/备份同步 | 偏好、网络、代理、更新、加密备份、WebDAV/S3/GitHub同步等 | 原生设置与 Android 文件选择器；同步默认关闭、显式配置，支持冲突/撤回与恢复；native 更新仅走 APK |
| 聊天测试（新增） | 原版有小请求探测，不等同完整聊天 | 多轮文本、流式/非流式、模型/路由组、取消、重试、能力允许时图片、路由/费用显示 |

桌面托盘、系统终端、桌面 Agent 安装及自启等，以 Android 通知/服务/教程替代，单独列为平台差异，不计为“原样移植完成”。本地 ML、RTK、Claude 订阅及未验证插件列为待支持，不用灰色按钮充当完成。

业务资料：[快速上手](https://usemagpie.ai/docs/zh/start)、[资源库](https://usemagpie.ai/docs/zh/library)、[MCP路由](https://usemagpie.ai/docs/zh/mcp-router)、[隐私](https://usemagpie.ai/docs/zh/privacy)、[插件](https://usemagpie.ai/docs/zh/plugins)。详细行为以固定源码测试为准。

## 4. 确定实现架构

```text
Compose 原生页面 / ViewModel
        │
        ├─ EngineController ─ Android GatewayService / FGS
        │                         └─ APK预置 Magpie CLI
        │                              └─ APK预置 Bun → 已验证JS插件
        │
        ├─ CoreClient → 127.0.0.1:3430 管理JSON API（独立管理凭据）
        ├─ ChatClient → 127.0.0.1:3425 网关（客户端密钥）
        └─ SAF / App私有存储 → 文件导入导出、聊天与UI状态

其他手机App / LAN客户端 → 3425，必须持有效客户端密钥
LAN客户端不得访问3430管理面
```

### 4.1 Android 宿主与执行载荷

- Kotlin、Jetpack Compose、ViewModel/StateFlow、协程、OkHttp；手机端最低 API 29，首轮以 target/compile SDK 36 验证，不通过降低 target 绕过系统限制。更高系统纳入后续兼容测试。
- Magpie/Bun 以 Android ARM64 **ELF PIE 可执行载荷**放入 APK 的 native library 目录，例如 `libmagpie_exec.so`、`libbun_exec.so`；它们不是拿来 `dlopen` 的普通共享库。
- 采用安装时提取的 native packaging，通过 `applicationInfo.nativeLibraryDir` 绝对路径和 `ProcessBuilder` 启动；保留载荷，避免构建工具错误 strip。`MAGPIE_BUN` 指向 APK 安装目录中的 Bun。
- `HOME`、XDG 配置/缓存、临时及 Bun 安装缓存目录均隔离到本 APK 私有目录；设置受控工作区，不扫描其他 App。
- 不从 assets/网络复制 native 到可写目录再 chmod/exec；禁止核心/Bun 自替换，native 仅随签名 APK 升级。命令参数使用数组、进程 stdin 传敏感数据，不提供通用 shell 执行入口。
- 复用上游 Android 构建规则，移动专用补丁独立启用，不改变桌面默认行为。优先从小补丁形成可维护 fork，不全面重写核心。

依据：[Android 10 W^X限制](https://developer.android.com/about/versions/10/behavior-changes-10)、[AOSP安装器](https://github.com/aosp-mirror/platform_frameworks_base/blob/android10-release/core/jni/com_android_internal_content_NativeLibraryHelper.cpp)。**该包装有平台依据，但必须在 release APK/真机中验证，不是跨所有 OEM 的已保证能力。**

### 4.2 CLI 与管理 API 的职责

- 一个服务持有一个长期核心，起始使用 `magpie serve --web=127.0.0.1:3430`；服务生命周期由 Android 管理，不运行 systemd/Termux daemon。
- 优先复用已核验接口：`/api/state`、`/api/providers`、`/api/provider/{action}`、`/api/settings`、`/api/profile/{action}`、`/api/library/...`、`/api/plugins/...`、`/api/sessions/...`、`/api/usage/...`。专用设置控制器逐项适配，不把所有设置假设成通用 key/value。
- CLI 仅用于已有合适结构化能力和受控维护，避免每个页面请求都重新启动插件宿主。
- 新增最小移动契约：鉴权后的 `GET /api/mobile/capabilities`，包含 `schemaVersion`、核心版本/提交、运行时版本及功能支持状态/原因。已有 API 保持形状，不冒充公开稳定 OpenAPI。
- 备份新增移动专用 export/import 接口，复用核心加密逻辑但只读写内部暂存文件；采用不透明暂存 ID，不接收任意绝对路径。原生层通过 SAF 流式导入/导出；导入默认 `SignIns=false`。
- 写操作复用核心锁/原子保存，并在客户端串行提交关联配置；超时后先重读真实状态，不自动重放登录、更新、恢复或聊天。未知字段保留，未知版本拒绝不安全写入。

### 4.3 管理面与客户端安全

桌面 localhost 的方便默认值不适合手机：**loopback 并不代表只有本 App 可以访问。**固定核心允许普通本机客户端使用占位 Key，必须加移动专用严格鉴权。

- 管理端仅 loopback；生成独立随机管理凭据，复用管理 cookie 机制，留在进程内，不出现在分享、Intent 或诊断日志。保留跨源防护。
- 网关含本机请求统一验证有效客户端 Key；App 内聊天使用独立 Key。密钥、配额、模型/账号权限、MCP 及统计读取都纳入鉴权测试；管理 cookie 与网关 Key 不通用。
- 移动构建关闭 heap/CPU/goroutine/restart 等本机调试入口及任意桌面安装/终端操作；危险管理动作使用能力白名单。
- LAN 默认关闭，显式开启后展示地址及每客户端密钥；不自动建隧道/公网转发。提供 TLS 配置；明文 LAN 须提示只用于可信网络，不把 HTTP 称为加密传输。
- API 密钥、同步凭据、登录令牌与请求正文不进入普通日志/Crash 报告；请求存档、OTLP 正文、同步均默认关闭。开启正文记录须提示敏感性、限制容量与保留时间。

证据：[管理cookie与跨源保护](https://github.com/yetone/magpie/blob/54dc257f100e2ca82a843b1eea6a24b8006781f5/internal/gui/web.go)、[网关实现](https://github.com/yetone/magpie/blob/54dc257f100e2ca82a843b1eea6a24b8006781f5/internal/gateway/gateway.go)。

### 4.4 原生页面与数据流

- 手机导航使用网关概览、提供商、聊天、更多功能入口；完整路由、Usage、Sessions、Library、Plugins、Settings 由原生页面展开。支持深浅色、中文、无障碍、键盘/旋转和长列表分页。
- Go 文件是提供商、模型、路由、资源库、Usage 及核心设置的唯一事实源；Kotlin 不另存一份可冲突的核心配置。
- DataStore 保存 UI 偏好与用户启停意图；Room 保存新增聊天、消息状态和本机操作状态。明确数据库版本及迁移；缓存失效后从核心重新读取。
- SAF 导入先复制至内部暂存，校验大小、格式、路径穿越/符号链接和覆盖冲突，再交核心处理；修改受控配置保留注释、原值、备份和恢复能力。导出不申请全盘文件权限。
- 加密备份复用核心格式；默认不携带订阅登录，导入覆盖前预览并保留恢复点。同步冲突必须展示选择，不直接用远端覆盖本机。

### 4.5 订阅、插件与工具

- 登录采用 Custom Tabs 或服务商允许的设备码流程，原生显示步骤、取消、超时、返回提示和轮询状态；不采集密码，不嵌入伪登录页。服务商取消/失效后销毁当前授权会话。
- 插件逐个记录：版本、来源、协议、授权方法、最低已验证 Android、外部程序/native 依赖及真实推理结果。发现与安装成功不等于可用。
- 私有目录可保存供预置 Bun 读取解释的 JS；禁止安装脚本自动运行、下载 native 执行、任意外部 CLI 及未验证 `.node` 依赖。git 来源由受控下载器取得锁定包，不默认嵌入完整 git/shell 工具链。
- 任意插件同 UID 运行，可能接触本 App 凭据；风险确认不是沙箱隔离。不能靠 UI 开关声称禁止了 Bun 全部文件或联网能力。预置宿主、下载器与执行能力门必须实际限制；无法约束的执行路径判不兼容。
- 自定义插件页面使用单独 WebView/资源加载器，不共享管理 cookie，不开放任意文件读取/JavaScript 原生桥；仅通过逐插件允许的消息协议交互，不向页面注入管理 token。
- HTTP MCP/已验证内置 stdio 工具优先；Hooks 与 RTK 可保存、生成配置，但未验证程序不执行。通用聊天不默认执行任意工具，模型工具调用展示后由用户显式允许的工具链处理。

### 4.6 后台服务与更新

- Android 14+ 采用真实用途的 `specialUse` FGS 及对应权限/用途说明；Android 10–13 采用普通 FGS。仅用户从可见页面/通知主动开启，及时显示常驻通知，提供停止。
- 不把全天网关伪装为 dataSync、媒体播放或 VPN。WorkManager 用于允许延后的同步/清理，不用于保活绕过用户停止。
- 区分用户停止、正常崩溃、网络断开和系统回收；只对确定退出且用户仍开启的核心作有限退避恢复。用户停止/强停后不自动复活，不重放未完成推理。
- 电池优化豁免通过系统页面由用户选择；仅必要活动请求短时持有唤醒锁。显示未豁免导致的熄屏网络风险，不承诺永久在线。
- APK、核心和 Bun 版本绑定；签名升级前备份、验证兼容再迁移。插件固定版本，更新前校验、等待已有流结束、失败恢复旧版本。

依据：[specialUse](https://developer.android.com/develop/background-work/services/fgs/service-types#special-use)、[dataSync时限](https://developer.android.com/develop/background-work/services/fgs/timeout)、[Doze](https://developer.android.com/training/monitoring-device-state/doze-standby)、[后台启动限制](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start)、[用户停止前台服务](https://developer.android.com/develop/background-work/services/fgs/handle-user-stopping)。

## 5. 主要风险、处理与退出条件

| 问题 | 处理方式 / 失败边界 |
|---|---|
| Bun 只有 Termux 能运行、最低 API 高于 10、动态库不满足 | 先检查 ELF 与真机；按确认的系统分级启用插件；全部失败则停下重新提问，不隐藏容器依赖 |
| native 打包、argv0 或 16KB 页不兼容 | 检查 Go/Bun 全部 ELF、依赖与页对齐；安装提取不能修复 ELF 问题；可维护重编译须核验许可，否则停止，不降 target/root 绕过 |
| 后台被 Doze/OEM/LMK/强停终止 | 明确状态、有限恢复、用户豁免指导；强停/未解锁等不能保证 LAN 在线 |
| 桌面账号/Agent 目录不可访问 | 受控模板、用户导入及对方 App 公开接口；不读取其他 App 私有凭据 |
| OAuth 回调、套餐/订阅规则变化 | 逐渠道 PoC 与能力开关；401/风控显示原因；不自动换付费 API、不规避限制 |
| 部分 SSE 已输出后断流/账号切换 | 保留部分回答并显示失败；不得拼接两个上游成一条伪完整回答，不自动重复扣费请求 |
| 插件/命令型 MCP 执行任意程序 | 版本/依赖检查与运行能力门；不兼容即拒绝，明确插件同 UID 风险 |
| 凭据、聊天、同步数据泄漏 | 私有目录/FBE、系统备份关闭、日志脱敏、加密导出、最小鉴权；root/已攻破系统不在保护承诺内 |
| 内部 API 漂移、文档滞后 | 固定提交、能力协商、契约回归；每次更新重新核验，不自动升级 |
| 手机内存、电量、存储不足 | 默认低并发、限制队列、分页、按需加载插件、记录实际 PSS/电耗；不静默下载大模型 |
| 许可证与订阅再分发 | Magpie MIT 保留声明；插件逐包审查；Bun 静态依赖含 LGPL，完成所选版本分发义务后才捆绑；Claude 另有商业条款，不当作 MIT 组件 |
| “绝大部分GUI”被缩减为启动器 | 功能点清单逐项验收；兼容替代和不可用分开计数，未完成不算通过 |

许可证资料：[Magpie MIT](https://github.com/yetone/magpie/blob/54dc257f100e2ca82a843b1eea6a24b8006781f5/LICENSE)、[Bun许可及依赖声明](https://github.com/oven-sh/bun/blob/main/LICENSE.md)、[Claude Code许可](https://github.com/anthropics/claude-code/blob/main/LICENSE.md)。Bun 实施前核验 1.4.2 对应材料及静态链接依赖义务，不能只附一条 MIT。

16KB 参考：[Android页大小支持](https://developer.android.com/guide/practices/page-sizes)。采用支持 16KB 的构建工具不等于预编译 Bun 已兼容；不能靠兼容模式代替验收。

## 6. 实施阶段与交付门槛

### P0：运行时、分发与接口 PoC——先决定值不值得进入完整开发

1. 取得并锁定源码/资产/运行时，检查哈希与许可证义务；检查 ARM64、PIE、PT_INTERP、DT_NEEDED、PT_LOAD 及 Termux/glibc 依赖。
2. 构建最小 release APK，验证安装目录执行 Go/Bun、JSON stdin/stdout、Node API、TLS/DNS/代理、最小插件；在 Android 10 与较新 16KB 设备分别测试。
3. 验证核心启停、管理鉴权、一次有预算限制的 API Key 推理、SSE 取消、私有持久化与清洁停止。
4. 单独 PoC ZCode 浏览器授权/轮询/推理，不以 API Key 替代这项结果。验证其他优先订阅并形成支持表。

**退出条件：**核心执行或本机鉴权不能成立则不进入完整 UI；所有系统上的 Bun 均不能独立运行则暂停插件路线并重新询问。JNI 共享库重构只作为另行确认的同 APK 备选，不偷偷切换 Termux/远程模式。

交付：可行性报告、精确支持矩阵、最小 APK、运行日志脱敏样例、许可证核验清单、继续/停止结论。

### P1：宿主、安全和数据基础

完成 Service/FGS、进程监督、端口/网络切换、管理与客户端严格鉴权、移动能力接口、文件桥、数据库及升级/备份骨架。关闭 native 自更、调试和不允许的桌面执行路径。

门槛：启动/停止/崩溃/用户停止没有孤儿端口；其他 App 无有效 Key 不能读取或消费网关；LAN 无法访问管理面。

### P2：可用原生 Alpha

完成提供商、模型、账户登录流程、网关/密钥、基础路由、原生聊天、额度与请求列表、规则脱敏及已验证插件。

门槛：至少一个真实 API Key 渠道完整通过；在具备兼容运行时的系统上，至少一个用户授权的订阅插件通过登录、模型列表和真实推理。此 Alpha 不是“GUI 绝大部分已完成”的发布结论。

### P3：大部分 GUI 覆盖 Beta

补齐高级路由/规则、Profiles/受控 Agent 配置、Usage 与 Sessions、Library 六类管理、HTTP MCP/OAuth/路由、插件发现/更新/自定义页、加密备份/同步、观测与工具交互。每个不支持的执行能力有清晰原因。

门槛：通过功能覆盖清单、数据恢复与安全回归后才作为首个“主要 GUI 功能覆盖版”交付。

### P4：真机稳定性与发布

覆盖 Android 10、13、14、15/16 及 16KB，进行熄屏/Doze/OEM、网络切换、升级迁移、长列表、并发/断流、恶意导入/插件与许可核验，产出签名 APK 及完整说明。

粗估完整 Beta 及稳定化为 **12–20 人周**，取决于原生页面数量、插件和真机问题；不是工期承诺或日历安排，P0 后依据结果再估。Claude 订阅、本地 ML/RTK 和上架商店不包含在该估算内。

## 7. 测试与最终验收

### 功能与协议
- 固定上游 GUI 操作清单，记录原版功能点、原生实现、平台替代、待支持及对应测试。报告“全部原版功能”与“确认可移植范围”两个分母；不可用不计完成。
- 目标：确认可移植范围 **至少90%** 功能点通过，提供商/模型/路由/网关/统计/Library 管理/备份/聊天等关键链路全部通过；剩余项列清单，不用总百分比掩盖缺失关键功能。
- 使用 mock 提供商覆盖 Chat Completions、Responses、Anthropic Messages、Gemini 的流式/非流式、推理、图片、工具 schema、token 统计、429/401/超时及嵌套路由。模型不支持的能力必须正确拒绝。
- 真实渠道测试需用户提供/授权账号与调用预算；不进行未经确认的额度重置、签到礼金、充值或付费切换。

### 安全与数据
- 本机恶意 App、跨源网页、LAN 无 Key/错误 Key、受限/过期 Key、管理 cookie 混用全部拒绝；MCP/统计/调试入口不能绕过。
- 日志、备份、错误、剪贴板/分享中不得出现未经用户明确展示的凭据；关闭系统备份。
- 验证备份错误口令、破损/超大包、路径穿越、同名资源冲突、同步撤回、升级前后恢复和导入登录默认关闭。
- 配置操作保持原值/注释和用户内容，超时不盲目重放；插件更新失败可恢复版本和配置。

### Android 与体验
- 测试 release APK 在 4KB/16KB 环境的 Go/Bun/TLS/插件执行，记录具体设备/API/页大小/哈希；兼容性只对实测范围声明。
- 启动、重复点击、切后台、旋转、熄屏、Doze 豁免开/关、Wi-Fi/移动网络切换、LMK、用户 Stop/ForceStop、重启未解锁均有明确状态；用户停止后不自动保活。
- SSE 按增量显示并可取消，部分失败保留已收到内容；停止服务后无遗留进程/监听，不重复请求。
- 统计/会话分页、空态/错误态、无障碍及原生核心页面全部检查；测量冷启动、API 延迟、PSS 和电耗，区分本地开销与上游时间，不在测量前承诺低内存或全天低耗。

## 8. 实施后的交付物与维护原则

交付包括：
1. Android 原生项目、最小化 Magpie 移动补丁、可复现构建和签名侧载 APK。
2. 固定版本/资产校验与依赖许可证清单，尤其 Bun 及插件的第三方声明。
3. GUI 功能覆盖表、Android/订阅/插件兼容表、内部 API 契约与升级回归测试。
4. 真机测试与性能报告、连接教程、隐私说明、后台限制说明、加密备份/恢复与升级指南。

维护上保持“原生宿主与 UI”和“上游核心补丁”分离；升级先跑契约、安全、协议、运行时和配置迁移测试，再发新版 APK。遇到新的功能取舍、许可障碍或支持范围变化，继续通过提问工具确认，不擅自削减目标或替换成本/部署方式。

**建议批准的下一步是 P0 可行性验证，而不是先绘制全部页面或宣布独立 APK 已经可用。P0 通过后依次推进 P1–P4；P0 失败按本计划的退出条件停止并报告。**
