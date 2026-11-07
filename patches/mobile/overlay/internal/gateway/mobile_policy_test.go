//go:build mobile

package gateway

import (
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/yetone/magpie/internal/access"
	"github.com/yetone/magpie/internal/budget"
	"github.com/yetone/magpie/internal/provider"
	"github.com/yetone/magpie/internal/settings"
)

func mobileTestKey(t *testing.T, readOnly bool) (string, access.Identity) {
	t.Helper()
	secret, err := access.Update("add-key", access.Change{Name: "Mobile test", ReadOnly: readOnly})
	if err != nil {
		t.Fatal(err)
	}
	who, ok := access.Authenticate(secret)
	if !ok || who.KeyID == "" {
		t.Fatal("core-issued key did not authenticate")
	}
	return secret, who
}

func TestMobileRequireKeyAllSources(t *testing.T) {
	fresh(t)
	if err := settings.Save(settings.Settings{LAN: true}); err != nil {
		t.Fatal(err)
	}
	secret, issued := mobileTestKey(t, false)
	seen := false
	h := lanGuard(callerGuard(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		seen = true
		who := access.Caller(r.Context())
		if who.KeyID != issued.KeyID || r.Header.Get("Authorization") != "Bearer "+Token {
			t.Error("identity lost or credential not normalized")
		}
		w.WriteHeader(http.StatusNoContent)
	})))
	for _, source := range []string{"ipv4", "ipv6", "remote", "proxy", "page-port"} {
		for _, presentation := range []string{"Authorization", "x-api-key", "x-goog-api-key", "query"} {
			for _, token := range []string{"", Token, TokenFor("alma"), "wrong", secret} {
				r := httptest.NewRequest("GET", "http://127.0.0.1:3425/v1/models", nil)
				r.RemoteAddr = "127.0.0.1:6000"
				switch source {
				case "ipv6":
					r.RemoteAddr = "[::1]:6000"
				case "remote":
					r.RemoteAddr = "192.168.1.20:6000"
				case "proxy":
					r.Header.Set("X-Forwarded-For", "192.168.1.20")
				case "page-port":
					r = FromPagePort(r)
				}
				if presentation == "query" {
					q := r.URL.Query()
					q.Set("key", token)
					r.URL.RawQuery = q.Encode()
				} else if presentation == "Authorization" {
					r.Header.Set(presentation, "Bearer "+token)
				} else {
					r.Header.Set(presentation, token)
				}
				seen = false
				w := httptest.NewRecorder()
				h.ServeHTTP(w, r)
				if token == secret {
					if w.Code != http.StatusNoContent || !seen {
						t.Fatalf("valid %s/%s: %d", source, presentation, w.Code)
					}
				} else if seen || (w.Code != http.StatusUnauthorized && w.Code != http.StatusTooManyRequests) {
					t.Fatalf("unkeyed %s/%s reached handler: %d", source, presentation, w.Code)
				}
			}
		}
	}
}

func TestMobileEmbeddedHandlerAndRevocation(t *testing.T) {
	fresh(t)
	secret, issued := mobileTestKey(t, false)
	h := New().Handler()
	call := func(token string) int {
		r := httptest.NewRequest("GET", "http://127.0.0.1:3425/", nil)
		r.RemoteAddr = "127.0.0.1:6000"
		r.Header.Set("Authorization", "Bearer "+token)
		w := httptest.NewRecorder()
		h.ServeHTTP(w, r)
		return w.Code
	}
	for _, token := range []string{"", Token, "wrong"} {
		if got := call(token); got != http.StatusUnauthorized {
			t.Fatalf("embedded handler accepted placeholder: %d", got)
		}
	}
	if call(secret) != http.StatusOK {
		t.Fatal("embedded handler refused valid key")
	}
	if _, err := access.Update("off-key", access.Change{Key: issued.KeyID}); err != nil {
		t.Fatal(err)
	}
	if call(secret) != http.StatusUnauthorized {
		t.Fatal("disabled key bypassed Authenticate")
	}
}

func TestMobileReadOnlyIdentity(t *testing.T) {
	fresh(t)
	secret, issued := mobileTestKey(t, true)
	if _, err := access.Update("limit-key", access.Change{Key: issued.KeyID, Limit: &access.Limit{Period: "day", Tokens: 1000}}); err != nil {
		t.Fatal(err)
	}
	mux := &routeMux{ServeMux: http.NewServeMux()}
	seen := false
	check := func(w http.ResponseWriter, r *http.Request) {
		seen = true
		who := access.Caller(r.Context())
		if who.KeyID != issued.KeyID || !who.ReadOnly || who.Limit == nil || who.Limit.Tokens != 1000 {
			t.Error("mobile lost caller's read-only/limit identity")
		}
		w.WriteHeader(http.StatusNoContent)
	}
	mux.HandleFunc("GET /v1/magpie/limit", check)
	mux.HandleFunc("POST /v1/chat/completions", check)
	h := callerGuard(readOnlyGuard(mux, mux))
	for _, method := range []string{http.MethodGet, http.MethodPost} {
		p := "/v1/magpie/limit"
		if method == http.MethodPost {
			p = "/v1/chat/completions"
		}
		r := httptest.NewRequest(method, "http://127.0.0.1:3425"+p, nil)
		r.RemoteAddr = "127.0.0.1:6000"
		r.Header.Set("Authorization", "Bearer "+secret)
		seen = false
		w := httptest.NewRecorder()
		h.ServeHTTP(w, r)
		if method == http.MethodGet && (w.Code != http.StatusNoContent || !seen) {
			t.Fatal("read-only stats refused")
		}
		if method == http.MethodPost && (w.Code != http.StatusForbidden || seen) {
			t.Fatal("read-only key reached a model route")
		}
	}
}

// limitedUpstream is the upstream test suite's local httptest fake, not a real
// model endpoint. This exercises the original budget middleware at zero cost.
func TestMobileLimitRemainsEnforced(t *testing.T) {
	fresh(t)
	budget.Forget()
	pinLimitClock(t)
	calls := limitedUpstream(t, 0)
	secret, issued := mobileTestKey(t, false)
	setLimit(t, issued.KeyID, &access.Limit{Period: "day", Tokens: 700})
	h := New().Handler()
	for i := 0; i < 3; i++ {
		r := httptest.NewRequest("POST", "http://127.0.0.1:3425/v1/chat/completions", strings.NewReader(chatReq))
		r.RemoteAddr = "127.0.0.1:6000"
		r.Header.Set("Authorization", "Bearer "+secret)
		w := httptest.NewRecorder()
		h.ServeHTTP(w, r)
		want := http.StatusOK
		if i == 2 {
			want = http.StatusTooManyRequests
		}
		if w.Code != want {
			t.Fatalf("mock request %d: %d want %d", i, w.Code, want)
		}
	}
	if calls.Load() != 2 {
		t.Fatal("spent key reached the local fake")
	}
}

func TestMobileDisabledGatewayRoutesAndDumpFirst(t *testing.T) {
	fresh(t)
	secret, _ := mobileTestKey(t, false)
	s := New()
	for _, entry := range []struct{ method, p string }{
		{"GET", ClaudeRunsPath}, {"GET", HeapPath}, {"GET", CPUPath},
		{"GET", GoroutinesPath}, {"POST", RestartPath}, {"POST", OIDCKeysPath},
		{"POST", provider.RemoteProvePath}, {"GET", KeyPagePath}, {"GET", keyPageData},
	} {
		for _, registered := range s.routes().patterns {
			if registered == entry.method+" "+entry.p {
				t.Fatalf("mobile registered disabled route %s", registered)
			}
		}
		r := httptest.NewRequest(entry.method, "http://127.0.0.1:3425"+entry.p, nil)
		r.RemoteAddr = "127.0.0.1:6000"
		r.Header.Set("Authorization", "Bearer "+secret)
		w := httptest.NewRecorder()
		s.Front().ServeHTTP(w, r)
		if w.Code != http.StatusNotFound {
			t.Fatalf("Front exposed %s: %d", entry.p, w.Code)
		}
	}
	called := false
	h := s.dumpFirst(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { called = true; w.WriteHeader(http.StatusNotFound) }))
	r := httptest.NewRequest("GET", "http://127.0.0.1:3425"+GoroutinesPath, nil)
	r.RemoteAddr = "127.0.0.1:6000"
	w := httptest.NewRecorder()
	h.ServeHTTP(w, r)
	if !called || w.Code != http.StatusNotFound || w.Header().Get(GoroutineStatsHeader) != "" {
		t.Fatal("dumpFirst bypass still executed")
	}
}

func TestMobileNoCallbackKeyException(t *testing.T) {
	fresh(t)
	addCallback("mobile-test-callback")
	defer dropCallback("mobile-test-callback")
	called := false
	h := lanGuard(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { called = true }))
	r := httptest.NewRequest("POST", "http://127.0.0.1:3425"+claudeMCPPath+"mobile-test-callback", nil)
	r.RemoteAddr = "127.0.0.1:6000"
	w := httptest.NewRecorder()
	h.ServeHTTP(w, r)
	if called || w.Code != http.StatusUnauthorized {
		t.Fatal("callback token was accepted without a gateway key")
	}
}
