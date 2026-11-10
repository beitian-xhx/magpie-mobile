## Magpie-Mobile 0.1.30-upstream-3622 (P7 上游核心升级)

### 核心升级（v0.1.3188 → v0.1.3622）
- **上游核心整体升级**：595 commits / 300 files（桌面 Agent/CLI 域为主），移动补丁冲突面经 compare 分析确认为零后执行；
- **补丁重钉**：`patches/mobile/manifest.json` 全量重算——sourceRevision → `02512343`，checks/patches 哈希按新源重算，resultSha256 本地重放 replacements 重算；
- **唯一语义适配**：lan.go 的 keyPageOpen 分支在新版扩展为 `(keyPageOpen(r) || caFileOpen(r))`，移动策略替换同步适配（`!mobilepolicy.Enabled` 前缀保持语义不变）；
- **Bun 运行时不变**：bun-v1.4.2 独立于 magpie 版本，插件兼容性延续；
- **Go 工具链不变**：上游 go directive 仍为 1.26.8；
- 全部契约检查（apply 模拟 19/19、Room v1、FGS manifest、16KB、隐私审计）本地通过后经 CI 全量回归验证。