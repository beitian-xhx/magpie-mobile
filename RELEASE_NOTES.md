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