//go:build mobile

package gui

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync/atomic"
	"testing"
)

func TestMobileModelsDiscoveryHTTPAuthAndNoVendorPOST(t *testing.T) {
	const manager = "model-discovery-management-key-012345"
	var vendorGets, vendorPosts atomic.Int64
	vendor := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method == "GET" { vendorGets.Add(1) } else { vendorPosts.Add(1) }
		if r.URL.Path != "/v1/models" || r.Header.Get("Cookie") != "" { t.Error("unexpected directory vendor request") }
		_, _ = w.Write([]byte(`{"data":[{"id":"catalog-choice","name":"Catalog choice"}]}`))
	}))
	defer vendor.Close()
	desktopCalls := 0
	h := webHandler("3430", manager, 0, nil, mobileAPI(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { desktopCalls++; w.WriteHeader(418) })))
	body, _ := json.Marshal(map[string]any{"chat": vendor.URL+"/v1", "key": "temporary-directory-secret-012345"})
	call := func(method, p, cookie, payload string) *httptest.ResponseRecorder {
		r := httptest.NewRequest(method, "http://127.0.0.1:3430"+p, strings.NewReader(payload))
		if cookie != "" { r.AddCookie(&http.Cookie{Name: "magpie_web_3430", Value: cookie}) }
		w := httptest.NewRecorder(); h.ServeHTTP(w, r); return w
	}
	for _, cookie := range []string{"", "wrong"} { if call("POST", "/api/mobile/providers/models", cookie, string(body)).Code != 401 { t.Fatal("discovery bypassed management cookie") } }
	if vendorGets.Load() != 0 { t.Fatal("unauthorized request contacted vendor") }
	result := call("POST", "/api/mobile/providers/models", manager, string(body))
	var catalog struct { Models []struct { ID, Name string }; BaseURL string `json:"baseUrl"` }
	if result.Code != 200 || json.Unmarshal(result.Body.Bytes(), &catalog) != nil || len(catalog.Models) != 1 || catalog.Models[0].ID != "catalog-choice" || catalog.BaseURL != vendor.URL+"/v1" { t.Fatal("directory HTTP response contract failed") }
	if strings.Contains(result.Body.String(), "temporary-directory-secret-012345") || vendorGets.Load() != 1 || vendorPosts.Load() != 0 || desktopCalls != 0 { t.Fatal("directory leaked key or used unsafe vendor/desktop action") }
	for _, method := range []string{"GET", "HEAD", "DELETE", "OPTIONS"} { if call(method, "/api/mobile/providers/models", manager, string(body)).Code != 404 { t.Fatal("non-POST discovery transport accepted") } }
	for _, path := range []string{"/api/provider/list", "/api/provider/test", "/api/provider/detect", "/api/mobile/providers/test", "/api/mobile/providers/detect"} { if call("POST", path, manager, string(body)).Code != 404 { t.Fatal("generic provider action opened") } }
	bad, _ := json.Marshal(map[string]any{"chat": vendor.URL+"/v1", "test": true})
	if call("POST", "/api/mobile/providers/models", manager, string(bad)).Code != 400 || vendorGets.Load() != 1 { t.Fatal("unknown discovery field contacted vendor") }
}

func TestMobileModelsDiscoveryHTTPFixedErrorAndCORS(t *testing.T) {
	const manager = "model-discovery-management-key-012345"
	const secret = "supplier-sensitive-error-key-012345"
	for _, status := range []int{401, 429} {
		var calls atomic.Int64
		vendor := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { calls.Add(1); w.WriteHeader(status); _, _ = w.Write([]byte(secret+" free-form vendor response")) }))
		h := webHandler("3430", manager, 0, nil, mobileAPI(http.NotFoundHandler()))
		body, _ := json.Marshal(map[string]string{"chat": vendor.URL+"/v1"})
		r := httptest.NewRequest("POST", "http://127.0.0.1:3430/api/mobile/providers/models", strings.NewReader(string(body)))
		r.AddCookie(&http.Cookie{Name: "magpie_web_3430", Value: manager})
		w := httptest.NewRecorder(); h.ServeHTTP(w, r)
		if w.Code != status || calls.Load() != 1 || strings.Contains(w.Body.String(), secret) || strings.Contains(w.Body.String(), "free-form") || strings.Contains(w.Body.String(), vendor.URL) { t.Fatal("upstream status error leaked credentials/body or retried") }
		r = httptest.NewRequest("POST", "http://127.0.0.1:3430/api/mobile/providers/models", strings.NewReader(string(body)))
		r.AddCookie(&http.Cookie{Name: "magpie_web_3430", Value: manager})
		r.Header.Set("Origin", "https://foreign.invalid"); r.Header.Set("Sec-Fetch-Site", "cross-site")
		w = httptest.NewRecorder(); h.ServeHTTP(w, r)
		if w.Code != 403 || calls.Load() != 1 { t.Fatal("discovery bypassed original CORS") }
		vendor.Close()
	}
}
