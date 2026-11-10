# Magpie-Mobile 结构评估复盘（2026-11-10，v0.1.30 态）

## 1. 全景数据

- app 模块生产代码总计约 5025 行，最大单文件 **326 行**（NativeNavigation.kt 主文件），其余全部 ≤300 行；
- P5-2 拆分成果：NativeNavigation.kt 由 **1206 行** 降至 326 行，11 张卡片迁至 GatewayCards(287)/RoutingCards(219)/SystemCards(219)/ChatCards(246) 四文件；
- core 模块职责单一，测试覆盖 48/48 文件（100%）。

## 2. 逐文件评估结论

| 文件 | 行数 | 裁定 | 依据 |
| :--- | ---: | :--- | :--- |
| NativeNavigation.kt | 326 | ✅ 已优化 | P5-2 完成 Dashboard 主体+页面路由，卡片已外迁 |
| ProvidersPanel.kt | 299 | ❌ 不拆 | ~22 个 remember 状态强耦合，拆分参数穿透风险大于收益（编辑对话框/搜索/模型选择共享状态） |
| GatewayCards.kt 等 4 文件 | 219~287 | ✅ 合理 | 单一职责卡片集合，拆分完成态 |
| ChatViewModel.kt | 268 | ✅ 合理 | 状态流+轮次管理内聚，测试覆盖完整 |
| ChatRepository.kt | 231 | ✅ 合理 | 事务边界清晰，单测覆盖 |
| AppUiPreferences.kt | 221 | ✅ 合理 | DataStore 编解码集中地 |
| GatewayService.kt | 209 | ✅ 合理 | 前台服务生命周期+supervisor 桥接，职责单一 |

## 3. 总结

结构优化目标达成：无超过 350 行的"上帝文件"，全部文件符合单一职责。
后续如 ProvidersPanel 新增功能导致持续膨胀（>450 行），再评估将编辑对话框（~150 行）外迁至 ProviderEditorDialog.kt。