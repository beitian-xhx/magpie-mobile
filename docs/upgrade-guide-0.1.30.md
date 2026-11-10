# Magpie-Mobile 0.1.30 真机安装与验证指南

**适用版本**: 0.1.30-upstream-3622（APK 86.3MB，SHA-256 `c49eb9bc335c2635f1c6859a017f7fb6554a8521de3bc1eb2ecbfcfe7dfd3c0b`）  
**适用设备**: 已安装 0.1.21 及以上版本的 Android 10+ / ARM64 设备  
**下载直链**: https://github.com/fplj-fplj/magpie-mobile/releases/download/v0.1.30-upstream-3622-04b34d55/magpie-mobile-0.1.30-upstream-3622.apk

---

## 一、安装步骤

1. 在设备浏览器打开上方直链（或从 Releases 页选择 0.1.30 资产），点击"下载"（约 86MB）；
2. 下载完成后，APK 位于 `/sdcard/Download/magpie-mobile-0.1.30-upstream-3622.apk`；
3. 在文件管理器中点击 APK 安装，或由自动化工具执行单命令：
   ```
   pm install -r /sdcard/Download/magpie-mobile-0.1.30-upstream-3622.apk
   ```
4. 固定签名与 0.1.21+ 一致（SHA-256 `dee1d3f485ee...`），**直接覆盖安装，无需卸载**，本地配置/会话全部保留。

## 二、启动与自动验证

1. 启动应用：
   ```
   am start -n cc.fplj.magpiemobile/.MainActivity
   ```
2. 网关就绪后，浏览器打开健康检查：`http://127.0.0.1:3431/healthz`（或 curl 单命令）：
   ```
   curl -s --max-time 5 http://127.0.0.1:3431/healthz
   ```
3. **判定规则**（P0-2 真机门槛）：healthz JSON 中 `magpie` 与 `bun` 字段均以 "ok" 开头 → PASS；
   另可核对 `coreVersion=0.1.3622-mobile.1`、`startupLatencyMs`（对标 800ms 门槛）与 `readyWithinBudget`。

## 三、0.1.22→0.1.30 新功能验证清单

| 功能 | 验证位置 | 预期 |
| :--- | :--- | :--- |
| 历史会话重命名/删除 | 聊天 → 历史会话对话框 | 非存档会话出现"重命名/删除"按钮；删除后自动回退最新会话 |
| 冷启动耗时显示 | 网关 → 版本与诊断 | 显示"冷启动就绪耗时 XXms（达标 <800ms）" |
| 用量洞察卡片 | 网关页 RecentRequestsCard 下方 | 点"读取用量洞察"出现聚合/热力图/额度/历史四段概要 |
| 开源许可声明 | 更多页底部 | 点"读取许可声明"显示 MIT/Apache-2.0 文本（64KB 内） |
| 系统稳定性卡 | 网关首屏 | 网络类型/电池优化/内存状态实时呈现 |

## 四、故障排查

- **安装报签名冲突**：确认旧版本 ≥0.1.21（更早版本使用 debug 签名，需先卸载，配置会丢失）；
- **healthz 无响应**：确认网关已启动（首页状态"运行中"），端口 3431 仅 loopback 监听；
- **升级后 Bun 插件异常**：0.1.30 的 Bun 仍为 v1.4.2，如异常请在"更多 → 订阅插件"执行回滚并反馈。