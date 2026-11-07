# Magpie 移动端最小补丁草稿（M1–M5）

基线：Magpie v0.1.3188 / commit 54dc257f100e2ca82a843b1eea6a24b8006781f5
性质：**设计草稿**——供向上游提 PR 评审用；未在本机编译（用户构建环境执行）。
设计原则（plan.md §4.1）：移动构建独立 tag/开关，桌面默认行为不变。

## M1 移动构建关闭 loopback 调试与管理入口

上游锚点（internal/gateway/gateway.go，Handler 链 L936）：
```
mux.HandleFunc("GET "+GoroutinesPath, s.goroutineDump)          // L1007 (loopback only)
// claude-code runs / heap profile / restart …（L998-1008 注释，均 loopback only）
```
方案：新增 build tag `mobile`（或 env `MAGPIE_MOBILE=1`）：
- 在 Handler 组装处，mobile 构建下不注册 GoroutinesPath / heap / cpu / restart / claude-runs 路由
- 返回 404 与"not available in mobile build"
测试：mobile tag 构建下 curl 上述路径 → 404；桌面构建不受影响（回归现有测试）。

## M2 loopback 客户端强制有效 gateway key

上游锚点：gateway.go L53 注释 "listens on loopback and accepts anything, but agents insist on one"；access.go Authenticate。
方案：mobile 构建下 callerGuard 对**所有**来源（含 loopback）要求有效 key（跳过 Token 占位接受逻辑）。
兼容开关：settings.json `mobileRequireKeys` 默认 true（mobile 构建），桌面默认 false。
测试：mobile 下无 key/错 key 请求 /v1/models → 401；桌面行为不变。

## M3 管理 --web 拒绝非 loopback 绑定

上游锚点：internal/gui/web.go StartWeb（L74-103，非 loopback 才走 TLS）。
方案：mobile 构建下 StartWeb 收到非 loopback addr 直接返回错误 "admin page is loopback-only in mobile build"。
测试：mobile 下 `magpie serve --web=0.0.0.0:3430` → 启动失败并给出说明。

## M4 备份/导出接口的内部暂存桥

上游锚点：internal/gui/backup.go downloads()（L281-287 写系统 Downloads）。
方案：导出接口增加可选参数 `sink`："downloads"（默认，桌面行为）| "app"（返回字节流或写入调用方给定描述符）。
移动壳用 "app" 接住字节流后经 SAF 写用户选择位置。不新增绝对路径输入（安全边界）。
测试：sink=app 时不再触碰系统 Downloads；字节流哈希与 Collect 一致。

## M5 GET /api/mobile/capabilities 能力协商契约

新增只读端点：返回 schemaVersion、magpie 版本/提交、mobile 构建 true、
M1-M4 生效状态、可用功能开关（loopbackKeys、localMaskingModel=false 等）。
移动壳启动时读取；版本不匹配 → UI 提示升级。测试：形状钉住 + 未知名忽略。

## 实施顺序与上游策略
1. 先开 issue 讨论移动构建需求（附本文件与真机证据：Android 16 exec 成功）
2. M1+M2+M3 一个 PR（同一主题：mobile 安全边界）；M4/M5 各一个 PR
3. 全部带回归测试（桌面行为不变），CI 上 go test ./...
