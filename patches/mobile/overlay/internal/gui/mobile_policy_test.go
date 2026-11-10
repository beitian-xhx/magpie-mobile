//go:build mobile

package gui

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"reflect"
	"strings"
	"testing"

	"github.com/yetone/magpie/internal/access"
)

func mobileCookieRequest(method, p, value string) *http.Request {
	r := httptest.NewRequest(method, "http://127.0.0.1:3430"+p, nil)
	r.RemoteAddr = "127.0.0.1:6000"
	if value != "" {
		r.AddCookie(&http.Cookie{Name: "magpie_web_3430", Value: value})
	}
	return r
}

func TestMobileCapabilitiesRequireManagementCookie(t *testing.T) {
	const key = "mobile-management-test-key-012345"
	old := Version
	Version = "mobile-test-version"
	t.Cleanup(func() { Version = old })
	// The real GUI Handler must install the mobile filter, not just a test mux.
	h := webHandler("3430", key, 0, Handler(webHost{quit: func() {}}, nil))
	for _, value := range []string{"", "wrong", key} {
		r := mobileCookieRequest("GET", "/api/mobile/capabilities", value)
		w := httptest.NewRecorder()
		h.ServeHTTP(w, r)
		if value != key {
			if w.Code != http.StatusUnauthorized {
				t.Fatal("capabilities bypassed webGuard", w.Code)
			}
			continue
		}
		if w.Code != http.StatusOK || w.Header().Get("Cache-Control") != "no-store" {
			t.Fatal("capabilities failed", w.Code)
		}
		var got map[string]any
		if err := json.Unmarshal(w.Body.Bytes(), &got); err != nil {
			t.Fatal(err)
		}
		want := map[string]any{"schemaVersion": float64(1), "coreVersion": Version, "mobile": true,
			"loopbackKeys": true, "debugEndpoints": false, "adminLoopbackOnly": true, "nativeAutoUpdate": false,
			"providerSchemaVersion": float64(1), "providerManagement": true, "providerModelDiscovery": true}
		if !reflect.DeepEqual(got, want) {
			t.Fatal("capabilities schema differs")
		}
	}
	// The original cookie exchange and same-origin/CORS barrier stay intact.
	r := httptest.NewRequest("GET", "http://127.0.0.1:3430/?k="+key, nil)
	w := httptest.NewRecorder()
	h.ServeHTTP(w, r)
	cookies := w.Result().Cookies()
	if w.Code != http.StatusSeeOther || len(cookies) != 1 || !cookies[0].HttpOnly || cookies[0].SameSite != http.SameSiteLaxMode {
		t.Fatal("original webGuard cookie contract changed")
	}
	r = mobileCookieRequest("GET", "/api/mobile/capabilities", key)
	r.Header.Set("Sec-Fetch-Site", "cross-site")
	w = httptest.NewRecorder()
	h.ServeHTTP(w, r)
	if w.Code != http.StatusForbidden {
		t.Fatal("foreign page bypassed webOwnPage")
	}
}

func TestMobileGUIExactWhitelist(t *testing.T) {
	const key = "mobile-management-test-key-012345"
	calls := 0
	h := webHandler("3430", key, 0, mobileAPI(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		calls++
		w.WriteHeader(http.StatusNoContent)
	})))
	allowed := []struct{ method, p string }{
		{"GET", "/api/caller-keys"}, {"GET", "/api/caller-keys/models"}, {"GET", "/api/state"}, {"GET", "/api/providers"},
		{"GET", "/api/usage/requests"}, {"GET", "/api/usage/heatmap"}, {"GET", "/api/usage/quotas"}, {"GET", "/api/usage/quotas/history"},
		{"GET", "/api/notices"}, {"GET", "/api/usage"},
		{"POST", "/api/caller-keys/add-key"}, {"POST", "/api/caller-keys/copy-key"},
	}
	for _, entry := range allowed {
		for _, value := range []string{"", "wrong", key} {
			before := calls
			w := httptest.NewRecorder()
			h.ServeHTTP(w, mobileCookieRequest(entry.method, entry.p, value))
			if value == key {
				if w.Code != http.StatusNoContent || calls != before+1 {
					t.Fatal("allowed API refused", entry, w.Code)
				}
			} else if w.Code != http.StatusUnauthorized || calls != before {
				t.Fatal("API reached without management cookie", entry)
			}
		}
	}
	for _, entry := range []struct{ method, p string }{
		{"POST", "/api/caller-keys/rotate-key"}, {"POST", "/api/caller-keys/off-key"}, {"POST", "/api/caller-keys/on-key"},
		{"POST", "/api/caller-keys/remove-key"}, {"POST", "/api/providers/save"}, {"POST", "/api/providers/test"},
		{"POST", "/api/library/mcp/check"}, {"POST", "/api/agents/run"}, {"GET", "/api/update"},
		{"POST", "/api/update"}, {"POST", "/api/restart"}, {"GET", "/api/backup"}, {"POST", "/api/backup/import"},
		{"GET", "/api/future"}, {"POST", "/api/state"}, {"HEAD", "/api/caller-keys"}, {"OPTIONS", "/api/providers"},
		{"POST", "/api/mobile/capabilities"}, {"GET", "/api/mobile/capabilities/"},
		{"POST", "/api/caller-keys/add-key/"}, {"GET", "/api//state"}, {"GET", "/api/other/../state"}, {"GET", "/api"},
	} {
		before := calls
		w := httptest.NewRecorder()
		h.ServeHTTP(w, mobileCookieRequest(entry.method, entry.p, key))
		if w.Code != http.StatusNotFound || calls != before {
			t.Fatalf("dangerous or unknown API reached desktop handler %s %s: %d", entry.method, entry.p, w.Code)
		}
	}
	r := mobileCookieRequest("GET", "/api/state", key)
	r.URL.RawPath = "/%61pi/state"
	w := httptest.NewRecorder()
	h.ServeHTTP(w, r)
	if w.Code != http.StatusNotFound {
		t.Fatal("escaped spelling bypassed allowlist")
	}
}

func TestMobileCallerKeyIssueAndCopy(t *testing.T) {
	t.Setenv("XDG_CONFIG_HOME", t.TempDir())
	const key = "mobile-management-test-key-012345"
	mux := http.NewServeMux()
	callerKeyRoutes(mux)
	h := webHandler("3430", key, 0, mobileAPI(mux))
	request := func(action, body, cookie string) *httptest.ResponseRecorder {
		r := httptest.NewRequest("POST", "http://127.0.0.1:3430/api/caller-keys/"+action, strings.NewReader(body))
		if cookie != "" {
			r.AddCookie(&http.Cookie{Name: "magpie_web_3430", Value: cookie})
		}
		w := httptest.NewRecorder()
		h.ServeHTTP(w, r)
		return w
	}
	if w := request("add-key", `{"name":"Mobile private"}`, ""); w.Code != http.StatusUnauthorized {
		t.Fatal("key issue without manager authentication")
	}
	w := request("add-key", `{"name":"Mobile private"}`, key)
	var result struct { Secret string }
	if w.Code != http.StatusOK || json.Unmarshal(w.Body.Bytes(), &result) != nil {
		t.Fatal("key issuance failed", w.Code)
	}
	who, ok := access.Authenticate(result.Secret)
	if !ok || !strings.HasPrefix(result.Secret, access.Prefix) || len(strings.TrimPrefix(result.Secret, access.Prefix)) != 48 {
		t.Fatal("not a real core-issued key")
	}
	body, _ := json.Marshal(map[string]string{"key": who.KeyID})
	if request("copy-key", string(body), "wrong").Code != http.StatusUnauthorized {
		t.Fatal("full key copied without manager authentication")
	}
	w = request("copy-key", string(body), key)
	var copied struct { Secret string }
	if w.Code != http.StatusOK || json.Unmarshal(w.Body.Bytes(), &copied) != nil || copied.Secret != result.Secret {
		t.Fatal("key recovery did not copy exact core key")
	}
	// Secrets are only compared in memory; no assertion prints their values.
}

func TestMobileStartWebRejectsNonLoopback(t *testing.T) {
	for _, addr := range []string{":3430", "0.0.0.0:3430", "[::]:3430", "192.168.1.1:3430", "example.com:3430", "localhost:3430", "invalid"} {
		if web, err := StartWeb(addr, "mobile-test"); err == nil || web != nil {
			t.Fatalf("mobile listened on non-literal-loopback %s", addr)
		}
	}
	for _, addr := range []string{"127.0.0.1:0", "127.0.0.2:3430", "[::1]:3430"} {
		if err := mobileWebAddr(addr); err != nil {
			t.Fatal("literal loopback refused", addr)
		}
	}
}

func TestMobileWebKeyMustBeAppInjectedAndProcessOnly(t *testing.T) {
	for _, key := range []string{"", "short", "0123456789abcdef x", "0123456789abcdef-safe"} {
		t.Setenv("MAGPIE_WEB_KEY", key)
		t.Setenv(webRunKey, "0123456789abcdef0123456789abcdef")
		got, fixed, err := webKey()
		if key == "0123456789abcdef-safe" {
			if err != nil || fixed || got != key {
				t.Fatal("valid App-injected management key refused")
			}
		} else if err == nil || got != "" {
			t.Fatal("missing/invalid key or reexec fallback accepted")
		}
		if os.Getenv("MAGPIE_WEB_KEY") != "" || os.Getenv(webRunKey) != "" {
			t.Fatal("management credential leaked to child environment")
		}
	}
}

func TestMobileNativeUpdatesStartDoesNothing(t *testing.T) {
	oldExecutable, oldBundleOf := executable, bundleOf
	t.Cleanup(func() { executable, bundleOf = oldExecutable, oldBundleOf })
	executable = func() (string, error) { t.Fatal("mobile updater looked for native executable"); return "", nil }
	bundleOf = func() string { t.Fatal("mobile updater looked for native bundle"); return "" }
	var u updater
	u.start()
	if u.exe != "" || u.bundle != "" || u.latest != nil {
		t.Fatal("mobile updater changed native state")
	}
}
