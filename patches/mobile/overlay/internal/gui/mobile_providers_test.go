//go:build mobile

package gui

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"strings"
	"sync/atomic"
	"testing"

	"github.com/yetone/magpie/internal/provider"
)

func TestMobileProviderHTTPAuthCreateAndRevision(t *testing.T) {
	t.Setenv("XDG_CONFIG_HOME", t.TempDir())
	const manager = "mobile-provider-management-session"
	var upstreamCalls atomic.Int64
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { upstreamCalls.Add(1); w.WriteHeader(500) }))
	defer upstream.Close()
	desktopCalls := 0
	h := webHandler("3430", manager, 0, nil, mobileAPI(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { desktopCalls++; w.WriteHeader(418) })))
	call := func(method, p, body, cookie string) *httptest.ResponseRecorder {
		r := httptest.NewRequest(method, "http://127.0.0.1:3430"+p, strings.NewReader(body))
		if cookie != "" { r.AddCookie(&http.Cookie{Name: "magpie_web_3430", Value: cookie}) }
		w := httptest.NewRecorder(); h.ServeHTTP(w, r); return w
	}
	payload, _ := json.Marshal(map[string]any{"new": true, "id": "mobile-http", "name": "Local HTTP", "chat": upstream.URL+"/v1", "key": "private-api-key", "models": []string{"raw-vendor-model"}, "noModels": false})
	for _, cookie := range []string{"", "wrong"} {
		if call("POST", "/api/mobile/providers/create", string(payload), cookie).Code != 401 { t.Fatal("create bypassed management authentication") }
		if call("GET", "/api/mobile/providers/editable/mobile-http", "", cookie).Code != 401 { t.Fatal("editable bypassed management authentication") }
		if call("POST", "/api/mobile/providers/edit", `{"id":"mobile-http","revision":"invalid"}`, cookie).Code != 401 { t.Fatal("edit bypassed management authentication") }
	}
	created := call("POST", "/api/mobile/providers/create", string(payload), manager)
	var snapshot provider.MobileProviderSnapshot
	if created.Code != 200 || json.Unmarshal(created.Body.Bytes(), &snapshot) != nil || snapshot.ID != "mobile-http" || len(snapshot.Revision) != 64 { t.Fatal("create failed", created.Code) }
	if strings.Contains(created.Body.String(), "private-api-key") || upstreamCalls.Load() != 0 { t.Fatal("create exposed key or contacted provider") }
	read := call("GET", "/api/mobile/providers/editable/mobile-http", "", manager)
	if read.Code != 200 || strings.Contains(read.Body.String(), "private-api-key") { t.Fatal("safe editable snapshot failed") }
	edit, _ := json.Marshal(map[string]any{"id": snapshot.ID, "revision": snapshot.Revision, "name": "Changed safely"})
	updated := call("POST", "/api/mobile/providers/edit", string(edit), manager)
	if updated.Code != 200 { t.Fatal("dirty edit failed", updated.Code) }
	stale := call("POST", "/api/mobile/providers/edit", string(edit), manager)
	if stale.Code != 409 || !strings.Contains(stale.Body.String(), "provider_revision_conflict") { t.Fatal("stale revision did not conflict") }
	if upstreamCalls.Load() != 0 || desktopCalls != 0 { t.Fatal("controlled API reached upstream or desktop mutation") }
	if info, err := os.Stat(provider.Path()); err != nil || info.Mode().Perm() != 0o600 { t.Fatal("provider config not private") }
}

func TestMobileProviderHTTPUnknownFieldsAndDangerousPaths(t *testing.T) {
	t.Setenv("XDG_CONFIG_HOME", t.TempDir())
	const manager = "mobile-provider-management-session"
	calls := 0
	h := webHandler("3430", manager, 0, nil, mobileAPI(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { calls++; w.WriteHeader(418) })))
	call := func(method, p, body string) *httptest.ResponseRecorder {
		r := httptest.NewRequest(method, "http://127.0.0.1:3430"+p, strings.NewReader(body))
		r.AddCookie(&http.Cookie{Name: "magpie_web_3430", Value: manager})
		w := httptest.NewRecorder(); h.ServeHTTP(w, r); return w
	}
	base := `{"new":true,"id":"mobile-unknown","name":"Test","chat":"https://api.example.com/v1","key":"synthetic","models":[],"noModels":true`
	for _, body := range []string{base+`,"test":true}`, base+`,"proxy":"http://evil.invalid"}`, base+`,"headers":{"Authorization":"secret"}}`, base+`,"new":true}`, base+`,"key":null}`, base+`,"New":true}`, base+`} {}`} {
		if got := call("POST", "/api/mobile/providers/create", body).Code; got != 400 { t.Fatal("unknown/duplicate/null/trailing fields accepted", got) }
	}
	for _, p := range []string{"/api/provider/save", "/api/provider/test", "/api/provider/detect", "/api/providers/save", "/api/mobile/providers/test", "/api/mobile/providers/detect", "/api/mobile/providers/delete", "/api/plugins/install"} {
		if got := call("POST", p, "{}").Code; got != 404 { t.Fatal("dangerous provider route exposed", p, got) }
	}
	for _, method := range []string{"GET", "HEAD", "OPTIONS", "DELETE"} {
		if got := call(method, "/api/mobile/providers/create", "{}").Code; got != 404 { t.Fatal("wrong method accepted") }
	}
	if calls != 0 { t.Fatal("unknown route reached desktop handler") }
	if _, err := os.Stat(provider.Path()); !os.IsNotExist(err) { t.Fatal("rejected input changed provider config") }
}

func TestMobileProviderHTTPCORSPreserved(t *testing.T) {
	const manager = "mobile-provider-management-session"
	h := webHandler("3430", manager, 0, nil, mobileAPI(http.NotFoundHandler()))
	r := httptest.NewRequest("POST", "http://127.0.0.1:3430/api/mobile/providers/create", strings.NewReader("{}"))
	r.AddCookie(&http.Cookie{Name: "magpie_web_3430", Value: manager})
	r.Header.Set("Origin", "https://foreign.invalid")
	r.Header.Set("Sec-Fetch-Site", "cross-site")
	w := httptest.NewRecorder(); h.ServeHTTP(w, r)
	if w.Code != 403 { t.Fatal("provider API bypassed original CORS barrier") }
}
