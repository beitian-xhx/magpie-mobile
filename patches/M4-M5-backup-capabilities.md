# M4+M5：备份桥与能力协商
# 基线同上。

=== M4 internal/gui/backup.go ===
export/import 请求体增加可选 "sink":"app"：
- sink 缺省/"downloads"：现行为（写系统 Downloads）不变
- sink="app"：跳过 downloads() 落盘，直接把 Seal() 字节经 HTTP 响应返回
  （Content-Disposition: attachment; filename=magpie-backup-<ts>.magpie-backup）
- import 增加 "source":"inline"，Data 字段已有 base64——保持兼容
安全：不新增绝对路径输入；sink=app 不写任何磁盘位置。
测试：sink=app 导出 → 响应字节 sha 与 Collect/Seal 一致；downloads 目录无新文件。

=== M5 新端点 GET /api/mobile/capabilities ===
响应形状（钉住）：
{
  "schemaVersion": 1,
  "magpie": {"version":"0.1.3188","commit":"<short>"},
  "mobile": {"build":true,"loopbackKeys":true,"debugEndpoints":false},
  "features": {"localMaskingModel":false,"rtk":false,"commandMcp":"limited"}
}
测试：形状钉住 + 未知字段忽略（客户端侧已有宽松解析先例）。

=== PR 策略 ===
- M1-M3 一个 PR："gateway: mobile build security boundary"
- M4 一个 PR："gui/backup: app sink for mobile SAF bridging"
- M5 一个 PR："api: mobile capabilities endpoint"
- 每个 PR 带桌面回归测试；先开 issue 附真机证据（Android 16 exec 成功截图/输出）
