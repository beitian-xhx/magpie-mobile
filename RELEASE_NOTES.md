## Magpie-Mobile 0.1.31-session-dialog-split (P7 结构优化延续)

### 结构优化（零行为变更）
- **ChatPanel 瘦身**：历史会话对话框（切换/重命名/删除/加载更多，约 90 行）外迁至 SessionHistoryDialog.kt，ChatPanel 由 154 行降至约 105 行；
- `chatDate` 工具函数随迁（internal，同包共用，ChatPanel 保留调用）；
- 纯代码移动：括号平衡、import 去重、调用点与定义分离核验通过，行为零变更。