# Magpie-Mobile（开发验证版）

目标与范围见 [移植计划](plan.md)。用户已确认 0.1.1 修复版在当前手机上启停正常；这不等于长时后台、多系统或付费渠道已验收。

## 当前里程碑：0.1.2-secure-core

本版本从固定 Magpie commit 54dc257f100e2ca82a843b1eea6a24b8006781f5 重新构建 Android ARM64 核心，加入真实 mobile,nogui 安全策略，而不是继续打包未补丁的官方 ELF。

- 本机与非本机客户端都必须持核心注册的有效 Key；占位值、错误、撤销、过期或受限 Key 不再退回匿名。
- 移动构建关闭 Claude runs、heap/CPU/goroutines、restart 等调试入口，也关闭 dumpFirst 旁路与 HTTP 临时密钥交换。
- 管理面仅允许 loopback。宿主固定网关 127.0.0.1:3425；不开放 LAN，不自动建隧道。
- native 自更不启动，危险/未验收管理 API 默认拒绝；当前仅允许安全能力、状态、提供商只读、客户端密钥注册及本App密钥只读恢复。
- 管理凭据每次 Service 生命周期独立随机生成，仅经环境注入并留在进程内；不共享给 WebView、Intent、状态流或诊断。
- 管理 3430 用专用 Cookie，网关 3425 用客户端 Bearer；不从错误端口的 hello 或 CLI 掩码表猜凭据。
- App Key 由核心签发，保存真实 id/name/secret 到私有 0600 文件（FBE保护、原子写）。重启重读核心，复用原密钥；撤销/受限不自动建替代Key。旧占位文件保留但不再用于请求。
- [能力接口与密钥存储](core/src/main/kotlin/cc/fplj/magpiemobile/core/CoreManagementClient.kt)遇版本/形状不兼容即拒绝写操作。写操作结果未知时先重读、不自动重发。

真实补丁及哈希清单位于 [移动补丁包](patches/mobile/manifest.json)，固定源码精确匹配应用；旧的 [M1–M5 设计稿](docs/upstream-patches-draft.md)仅作历史设计，不是编译依据。

## CI 验证

Actions 验证实际 manifest、Python补丁应用器回归、Kotlin core/app JVM回归、真实Go移动鉴权回归和Android ARM64 PIE构建。最后比对APK里的CLI与刚构建载荷SHA256，发布独立提交SHA的APK/校验值/核心构建信息。Bun继续固定1.4.2并核对官方ZIP哈希。

当前源码新增测试不等于测试已通过；以对应提交的Actions和报告为准。JVM/主机Go回归不是实体手机新增安全连接的运行验收。

## 新安全版本的手机验收

1. 安装 0.1.2-secure-core 独立Release包，核对页面版本/提交SHA；保留旧应用数据，遇签名冲突先回报，不卸载清数据绕过。
2. 启动网关，状态仍显示端口就绪，然后“核心安全连接”显示鉴权/注册完成。
3. 停止并重新启动，客户端密钥应复用，不能每次增加同名Key；未开启通知也能在页面看到结果。
4. 可刷新模型目录，但有模型/密钥不代表已经配置真实API渠道。不得以默认模型列表冒充推理通过。
5. 如连接失败，发版本、状态卡和安全连接错误；不贴完整密钥。

## 后续计划与边界

- 提供商创建/编辑、真实API和订阅接入将按固定 /api/provider/save 等接口逐步实现；provider test/detect 会实际调用模型并可能多次重试，必须另获授权和预算。
- 本阶段不调用真实模型、不导入其它App私有凭据、不完成收费/签到/充值/额度重置。
- M4加密备份/SAF桥、数据库、高级路由、已验证插件、长时后台、多系统/16KB完整测试和许可分发义务仍待完成。
- Claude订阅、本地ML、RTK与未验证stdio程序限制见实施计划；同UID插件不是沙箱。
