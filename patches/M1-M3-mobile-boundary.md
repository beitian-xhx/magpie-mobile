# M1+M2+M3：移动构建安全边界（mobile tag）
# 基线：Magpie v0.1.3188 commit 54dc257f
# 原则：桌面默认行为不变；mobile 构建（-tags mobile 或 MAGPIE_MOBILE=1）收紧。

=== 1) 新文件 internal/gateway/mobile.go ===
//go:build mobile

package gateway

import "os"

// MobileBuild reports whether this binary was built with the mobile tag
// or started with MAGPIE_MOBILE=1. Mobile builds tighten the security
// defaults: no debug endpoints, gateway keys required even on loopback,
// and an admin page that refuses non-loopback binds.
func MobileBuild() bool {
	if os.Getenv("MAGPIE_MOBILE") == "1" {
		return true
	}
	return mobileTag
}

const mobileTag = true

=== 2) internal/gateway/mobile_default.go（桌面默认） ===
//go:build !mobile

package gateway

const mobileTag = false

=== 3) internal/gateway/gateway.go Handler 组装处（L936 一带）增量 ===
（在 mux 注册 GoroutinesPath / heap / cpu / restart / claude-runs 之前判断）
if MobileBuild() {
	// M1: skip debug & admin-trigger routes entirely
} else {
	mux.HandleFunc("GET "+GoroutinesPath, s.goroutineDump)
	…（原有注册）
}

=== 4) callerGuard 增量（M2） ===
在 loopback 占位 Token 接受分支前：
if MobileBuild() && !hasValidKey(r) {
	writeError(w, provider.Chat, http.StatusUnauthorized,
		"mobile builds require a gateway key on every request, loopback included")
	return
}

=== 5) internal/gui/web.go StartWeb 增量（M3） ===
if MobileBuild() && !isLoopbackAddr(addr) {
	return nil, fmt.Errorf("admin page is loopback-only in mobile builds; use the app UI instead of %q", addr)
}

=== 测试计划（上游 CI 跑） ===
- mobile tag 构建：GoroutinesPath/heap/cpu/restart → 404；loopback 无 key → 401；--web=0.0.0.0 → 启动失败
- !mobile 构建：以上全部保持原行为（现有测试全绿即为回归证明）
