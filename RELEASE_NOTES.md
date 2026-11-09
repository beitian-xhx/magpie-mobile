## Magpie-Mobile 0.1.20-stability-lifecycle (P4-1)

### 核心功能与特性更新
- **网络连接状态与热切换感知 (Network Hot-Switching)**:
  - 引入 `NetworkConnectivityMonitor`，实时监听移动蜂窝 (Cellular)、Wi-Fi 与以太网切换；
  - 自动上报互联网有效性 (`NET_CAPABILITY_VALIDATED`)，为流式请求与重放保护提供网络状态底座。
- **Android 系统级电源管理与 Doze 白名单治理 (Power Governance)**:
  - 实现 `BatteryOptimizationHelper`，检测系统电池优化豁免状态 (`isIgnoringBatteryOptimizations`)；
  - 提供安全的系统设置引导 Intent，防止后台服务被系统省电策略与低内存杀手（LMK）异常冻结或终止。
- **低内存告警与系统事件联动 (Memory & LifeCycle)**:
  - 实现 `StabilityViewModel`，注册 `ComponentCallbacks2` 实时感知 `TRIM_MEMORY_RUNNING_CRITICAL` 与 `onLowMemory`；
  - 内存承压时在网关主界面抛出告警提示，引导用户控制多模型并发。
- **原生 UI 状态卡片**:
  - 首屏挂载 `SystemStabilityCard`，直观呈现当前网络拓扑、电源优化状态并支持一键刷新与设置跳转。
- **Release 自动化集成**:
  - CI 流水线全面支持自动从 `RELEASE_NOTES.md` 读取并同步版本更新日志至 GitHub Release 页面。
