## Magpie-Mobile 0.1.21-telemetry-recovery (P4-2)

### 核心功能与特性更新
- **冷启动耗时与就绪性能基准 (Cold-Start Benchmark)**:
  - 建立 StartupBenchmark 遥测体系，测量从进程加载 (ProcessInit) 到网关双端口监听就绪 (GatewayReady) 的完整毫秒耗时；
  - 自动对标 plan.md §6 中 800ms 的就绪黄金门槛，标记达标状态。
- **进程异常退出与崩溃事后审计 (Post-Mortem Auditor)**:
  - 实现 PostMortemAuditor，在应用私有沙箱中记录进程非预期退出时的状态 (Phase)、退出码及脱敏堆栈摘要；
  - 彻底杜绝密码与密钥泄露，冷启动后自动展示异常诊断并提供一键清理。
- **本地健康与遥测接口增强 (Healthz Endpoint)**:
  - 升级 HealthServer (/healthz)，暴露 startupLatencyMs 与 readyWithinBudget 遥测字段，方便本地观测与自动化测试。
- **UI 诊断卡片深度集成**:
  - 在设置与诊断面板中动态呈现冷启动性能指标与历史异常退出日志。