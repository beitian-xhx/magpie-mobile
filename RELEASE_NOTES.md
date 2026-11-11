## Magpie-Mobile 0.1.35-model-speeds-catalog (P8 功能对齐 A5+A4)

### 新端点对接（核心 3781）
- **A5 模型速度排行**：GET /api/model-speeds 白名单放行 + CoreManagementClient.modelSpeeds() 弹性解析（ttft/tps/n），网关页新增「本周模型速度排行」卡片（top 6，首 token 毫秒 + 输出 tok/s）；
- **A4 模型目录**：GET /api/catalog/models 白名单放行 + catalogModels() 解析，网关页新增「模型目录（models.dev 已同步）」卡片（条目总数 + 前 8 条 id/名称/上下文窗口）；
- 安全口径延续：只读、仅聚合元数据、解析失败空表、绝不呈现原始正文；白名单仍为精确路径 allowlist；
- 单测新增 ModelSpeedsCatalogParsingTest（5 例：典型/缺字段/畸形/空白/错误形状）。

### 版本
- versionCode 36；核心 0.1.3781-mobile.1 不变；备份包 APP_VERSION 0.1.35。

## Magpie-Mobile 0.1.34-upstream-3781 (P8 核心升级：对齐上游 270 commits)

### 核心升级 3622 → 3781（b52c711142a662fe18fedf5a80cb1e4aa94bce62）
- 上游 270 commits / 909 文件：新增 Agent 管理、订阅账号、隐私脱敏（PII masking）、意图路由、网关中间件、搜索/生图 MCP 工具等 40+ 功能域（详 docs/p8-gap-3781.md）；
- 移动补丁包重定位：34 条替换锚点 33 条直接命中，仅 lan.go 身份注入 1 条按新上下文（aliasRequest 别名行）重推导；apply 模拟测试 19/19 通过；
- 安全策略完整保留：mobilepolicy 开关、受管密钥、loopback 门禁与 callerGuard 认证路径全部延续；
- API 面纯增量（22 条新 /api 路由、0 删除），移动白名单为精确路径 allowlist，新桌面路由在 mobile 下自动 404 无暴露面；
- Go 1.26.8 不变；nogui,mobile 标签不变；16KB 页面对齐与固定签名链路不变。

### 版本
- versionCode 35；CORE_VERSION 0.1.3781-mobile.1；备份包 APP_VERSION 0.1.34。

## Magpie-Mobile 0.1.33-structure-uikit (P8 结构优化第三阶段)

### 结构优化（零行为变更）
- **SectionCard 原语抽取**：新增 UiBits.kt 共享 UI 组件，统一「卡片 + 12dp 内距 + 8dp 纵向间距 + 标题」骨架；
- NativeNavigation 六处手写 Card{Column{…}} 内联卡（网关状态/核心安全连接/本机界面偏好/版本与诊断/引擎状态/安全备份）全部替换为 SectionCard；
- 效果：NativeNavigation 326→308 行，卡片布局令牌单点维护（与 Anthropic 主题的卡片规范一致），括号平衡核验通过，行为零变更。

## Magpie-Mobile 0.1.32-anthropic-theme (P8 UI 风格重塑)

### Anthropic 风格主题（anthropic-style-frontend-cn 技能 · Compose 适配 · 工具优先模式）
- **暖米色大地调**：页面基底 #ECE9E0（不用纯白），卡片抬升色 #F5F3EC，深色反相区块 #141413；
- **唯一强调色陶土橙 #D97757** 承担主 CTA；危险操作使用错误红 #C0453A 强调；
- **品牌字体落地**：标题/正文 Lora（衬线）、UI/按钮/标签 Poppins（无衬线），共 7 枚字重文件内置 res/font，SIL OFL 许可随包分发（assets/licenses/ofl-lora.txt、ofl-poppins.txt）；
- **明暗双主题令牌**：完整 colorScheme/typography/shapes 三组 Token（theme/UiTheme.kt），全应用零硬编码颜色，一处接入（MainActivity）；
- **工具优先模式**：管理界面保持既有信息密度与 12dp 卡片节奏，仅重设色彩/字体/圆角，不牺牲可读性。

### 版本
- versionCode 33；核心 0.1.3622-mobile.1 不变；备份包 APP_VERSION 0.1.32。

## Magpie-Mobile 0.1.31-session-dialog-split (P7 结构优化延续)

### 结构优化（零行为变更）
- **ChatPanel 瘦身**：历史会话对话框（切换/重命名/删除/加载更多，约 90 行）外迁至 SessionHistoryDialog.kt，ChatPanel 由 154 行降至约 105 行；
- `chatDate` 工具函数随迁（internal，同包共用，ChatPanel 保留调用）；
- 纯代码移动：括号平衡、import 去重、调用点与定义分离核验通过，行为零变更。