# 0.1.4 主要流程 UX 审查

依据当前源代码与用户明确反馈，不把未执行的截图或像素观察称为真机验证。

## 本轮修改（待对应最终CI/手机验收）

| 0.1.3问题 | 修正 |
|---|---|
| 新建默认手填模型ID | URL/凭据稳定后自动只读目录、勾选；手填高级fallback |
| URL要求理解/v1、完整endpoint | 规范化、实际Base提示、不double/v1 |
| ID必填排首位 | 自动名称/唯一标识建议，ID高级可选 |
| 普通保存两次确认 | 单保存；只有明确禁全部模型确认 |
| 模型刷新丢/误改implicitAll | 纯选择策略保留已有与缺失模型、不touch编辑 |
| 聊天busy误当断开而关草稿 | 真实连接与操作busy分开，共享操作门 |
| 保存清选中和隐藏history | 自动刷新保留有效模型，不因名称改动清上下文 |
| 读失败锁只存在临时client | owner-bound客户端缓存，failed read阻写至健康刷新 |
| Unknown取消后可直接新建 | saveable非敏感pending marker跨表单待核对，不放Key |
| create409当版本冲突 | 专用AlreadyExists、edit409重读 |
| 格式错误后被迫重填Key | 本地先验，真正write尝试/未知后清Key |
| 表单在50项列表后 | 独立编辑页、表单旁错误、底部保存与IME处理 |
| 错误泄raw SSE/失败user重复 | 固定安全分类，partial仍失败；回滚pending user，保留草稿 |
| Stop立即宣称已退出 | 回收中状态、worker确认后开放Start |

## 自动目录安全约束

仅读取模型metadata，不调用供应商POST/chat/test/detect，不保存Provider/revision或修改选中模型；禁止全部redirect、Key/cookie/bearer跨目标转发。编辑storedKey只在同scheme/host/effectiveport且fresh revision时使用，跨origin必须新Key。结果受owner、URL/凭据指纹、请求token保护。限8秒、2MiB、1000项（客户端可更严）；错误固定，不显示自由vendor body。

## 仍需后续验证/扩展

实际Android IME、横屏/窄屏/大字体、通知channel禁用、服务强停/长时回收与真实目录兼容需用户手机确认；代码声明insets不等于真机无遮挡。模型搜索/列表截断有提示，复杂provider类型只读；完整导航、DB消息列表、取消网络句柄/长流容量、多协议目录仍未完整实现。真实目录可读也不证明推理可用或保证所有服务商免费。
