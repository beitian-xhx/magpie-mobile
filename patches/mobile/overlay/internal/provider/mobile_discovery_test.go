//go:build mobile

package provider

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"net/http/httptest"
	"os"
	"strings"
	"sync/atomic"
	"testing"
	"time"
)

func mobileDiscoveryStatus(t *testing.T, err error, status int, code string) {
	t.Helper()
	var got *MobileProviderModelsError
	if !errors.As(err, &got) || got.Status != status || got.Code != code { t.Fatal("discovery error contract differs") }
}

func TestMobileModelsDiscoveryGETOnlyAndPublicCatalog(t *testing.T) {
	mobileProviderHome(t)
	var gets, posts atomic.Int64
	var auth atomic.Value
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method != "GET" { posts.Add(1); w.WriteHeader(500); return }
		gets.Add(1); auth.Store(r.Header.Get("Authorization"))
		if r.URL.Path != "/v1/models" || r.Header.Get("Cookie") != "" || r.Header.Get("Accept-Encoding") != "identity" { t.Error("request was not a private GET model directory") }
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(`{"data":[{"id":"vendor-raw","name":"Vendor raw"},{"id":"vendor-raw"}]}`))
	}))
	defer server.Close()
	catalog, err := MobileDiscoverModels(context.Background(), MobileModelDiscoveryRequest{Chat: server.URL+"/v1/chat/completions"})
	if err != nil || len(catalog.Models) != 1 || catalog.Models[0].ID != "vendor-raw" || catalog.BaseURL != server.URL+"/v1" { t.Fatal("public model catalog failed", err) }
	if auth.Load().(string) != "" { t.Fatal("public directory sent an invented key") }
	key := "catalog-private-token-012345"
	catalog, err = MobileDiscoverModels(context.Background(), MobileModelDiscoveryRequest{Chat: server.URL+"/v1", Key: &key})
	if err != nil || len(catalog.Models) != 1 || auth.Load().(string) != "Bearer "+key || gets.Load() != 2 || posts.Load() != 0 { t.Fatal("keyed directory request failed") }
	if _, err := os.Stat(Path()); !os.IsNotExist(err) { t.Fatal("directory read created provider config") }
}

func TestMobileModelsDiscoveryStoredKeyRevisionAndOrigin(t *testing.T) {
	mobileProviderHome(t)
	var calls atomic.Int64
	var auth atomic.Value
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		calls.Add(1); auth.Store(r.Header.Get("Authorization"))
		if r.Method != "GET" { t.Error("directory sent non-GET") }
		_, _ = w.Write([]byte(`{"data":[{"id":"saved-choice"}]}`))
	}))
	defer server.Close()
	draft := mobileCreateDraft(); draft.Chat = server.URL+"/v1"; draft.Key = "saved-catalog-private-key-012345"
	snapshot, err := MobileCreateProvider(draft); if err != nil { t.Fatal(err) }
	before, _ := os.ReadFile(Path()); info, _ := os.Stat(Path())
	blank := ""
	request := MobileModelDiscoveryRequest{Chat: server.URL+"/v1", ID: &snapshot.ID, Revision: &snapshot.Revision, Key: &blank}
	catalog, err := MobileDiscoverModels(context.Background(), request)
	if err != nil || len(catalog.Models) != 1 || auth.Load().(string) != "Bearer "+draft.Key { t.Fatal("stored key was not reused privately") }
	after, _ := os.ReadFile(Path()); afterInfo, _ := os.Stat(Path())
	if !bytes.Equal(before, after) || !info.ModTime().Equal(afterInfo.ModTime()) { t.Fatal("read-only discovery changed provider file") }
	same, err := MobileEditableProvider(snapshot.ID); if err != nil || same.Revision != snapshot.Revision || len(same.Models) != 1 || same.Models[0] != "raw-model" { t.Fatal("discovery changed revision or selection") }
	var otherCalls atomic.Int64
	other := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { otherCalls.Add(1); auth.Store(r.Header.Get("Authorization")); _, _ = w.Write([]byte(`{"data":[]}`)) }))
	defer other.Close()
	request.Chat = other.URL+"/v1"
	_, err = MobileDiscoverModels(context.Background(), request)
	mobileDiscoveryStatus(t, err, 400, "provider_models_origin_changed")
	if otherCalls.Load() != 0 { t.Fatal("stored key leaked to changed origin") }
	override := "temporary-catalog-key-987654"
	request.Key = &override
	if _, err := MobileDiscoverModels(context.Background(), request); err != nil || otherCalls.Load() != 1 || auth.Load().(string) != "Bearer "+override { t.Fatal("explicit read-only override failed") }
	after, _ = os.ReadFile(Path()); if !bytes.Equal(before, after) { t.Fatal("override key saved to provider") }
	name := "new canonical name"
	if _, err := MobileEditProvider(MobileProviderEdit{ID: snapshot.ID, Revision: snapshot.Revision, Name: &name}); err != nil { t.Fatal(err) }
	request.Chat = server.URL+"/v1"; request.Key = nil
	beforeCalls := calls.Load()
	if _, err := MobileDiscoverModels(context.Background(), request); !errors.Is(err, ErrMobileProviderRevision) || calls.Load() != beforeCalls { t.Fatal("stale revision contacted vendor") }
}

func TestMobileModelsDiscovery404OnlyBoundedFallbackAndPaths(t *testing.T) {
	for _, entry := range []struct{ input, first, second string }{
		{"", "/models", "/v1/models"}, {"/v1", "/v1/models", "/models"},
		{"/v1/chat/completions", "/v1/models", "/models"}, {"/chat/completions", "/models", "/v1/models"},
		{"/v1/responses", "/v1/models", "/models"}, {"/api/v1/", "/api/v1/models", "/api/models"},
		{"/api", "/api/models", "/api/v1/models"}, {"/v1/v1", "/v1/models", "/models"},
	} {
		var calls atomic.Int64
		server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			n := calls.Add(1)
			if r.Method != "GET" || strings.Contains(r.URL.Path, "/v1/v1") { t.Error("bad directory path or method") }
			if n == 1 { if r.URL.Path != entry.first { t.Error("first path differs") }; w.WriteHeader(404); return }
			if r.URL.Path != entry.second { t.Error("second path differs") }
			_, _ = w.Write([]byte(`{"data":[]}`))
		}))
		catalog, err := MobileDiscoverModels(context.Background(), MobileModelDiscoveryRequest{Chat: server.URL+entry.input})
		if err != nil || calls.Load() != 2 || catalog.Models == nil || len(catalog.Models) != 0 || catalog.BaseURL != server.URL+strings.TrimSuffix(entry.second, "/models") { t.Fatal("root/v1 directory fallback failed") }
		server.Close()
	}
}

func TestMobileModelsDiscoveryFixedStatusesAndNoRetry(t *testing.T) {
	const secret = "upstream-sensitive-token-123456"
	for _, entry := range []struct{ status int; code string }{{401, "provider_models_key_required"}, {429, "provider_models_rate_limited"}, {403, "provider_models_forbidden"}, {503, "provider_models_upstream_error"}} {
		var calls atomic.Int64
		server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { calls.Add(1); w.WriteHeader(entry.status); _, _ = w.Write([]byte(secret+" vendor free-form error")) }))
		_, err := MobileDiscoverModels(context.Background(), MobileModelDiscoveryRequest{Chat: server.URL+"/v1"})
		mobileDiscoveryStatus(t, err, entry.status, entry.code)
		if calls.Load() != 1 || strings.Contains(err.Error(), secret) || strings.Contains(err.Error(), "vendor free-form") { t.Fatal("status error leaked body or retried") }
		server.Close()
	}
}

func TestMobileModelsDiscoveryRejectsRedirectsIncludingSameOrigin(t *testing.T) {
	var targetCalls atomic.Int64
	target := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { targetCalls.Add(1) }))
	defer target.Close()
	for _, location := range []string{target.URL+"/models", "/new/models"} {
		var calls atomic.Int64
		server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { calls.Add(1); w.Header().Set("Location", location); w.WriteHeader(302) }))
		key := "redirect-private-key-123456"
		_, err := MobileDiscoverModels(context.Background(), MobileModelDiscoveryRequest{Chat: server.URL+"/v1", Key: &key})
		mobileDiscoveryStatus(t, err, 502, "provider_models_redirect_rejected")
		if calls.Load() != 1 || targetCalls.Load() != 0 { t.Fatal("redirect followed with key") }
		server.Close()
	}
}

func TestMobileModelsDiscoveryRejectsMalformedOversizeAndSecretEcho(t *testing.T) {
	const key = "catalog-echo-private-key-123456"
	for _, entry := range []struct{ body, code string }{
		{"", "provider_models_invalid"}, {"<html>not catalog</html>", "provider_models_invalid"},
		{`{"models":[]}`, "provider_models_invalid"}, {`{"data":null}`, "provider_models_invalid"},
		{`{"data":[{}]}`, "provider_models_invalid"}, {`{"data":[{"id":"a","id":"b"}]}`, "provider_models_invalid"},
		{`{"data":[{"id":`+mobileDiscoveryJSONQuote(key)+`}]}`, "provider_models_sensitive_content"},
		{`{"data":[{"id":"safe","name":`+mobileDiscoveryJSONQuote(key)+`}]}`, "provider_models_sensitive_content"},
		{strings.Repeat("x", mobileModelsBytes+1), "provider_models_too_large"},
	} {
		server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { _, _ = w.Write([]byte(entry.body)) }))
		_, err := MobileDiscoverModels(context.Background(), MobileModelDiscoveryRequest{Chat: server.URL+"/v1", Key: mobileDiscoveryStringPointer(key)})
		mobileDiscoveryStatus(t, err, 502, entry.code)
		if strings.Contains(err.Error(), key) { t.Fatal("invalid catalog error exposed key") }
		server.Close()
	}
	rows := make([]map[string]string, mobileModelsCount+1)
	for i := range rows { rows[i] = map[string]string{"id":"same-id"} }
	body, _ := json.Marshal(map[string]any{"data": rows})
	if _, err := mobileParseModels(body, ""); err == nil { t.Fatal("oversize model count accepted") }
}

func mobileDiscoveryStringPointer(s string) *string { return &s }
func mobileDiscoveryJSONQuote(s string) string { b, _ := json.Marshal(s); return string(b) }

func TestMobileModelsDiscoveryLookupRespectsDeadline(t *testing.T) {
	mobileProviderHome(t)
	snapshot, err := MobileCreateProvider(mobileCreateDraft()); if err != nil { t.Fatal(err) }
	mobileProviderMu.Lock()
	defer mobileProviderMu.Unlock()
	ctx, cancel := context.WithTimeout(context.Background(), 25*time.Millisecond); defer cancel()
	_, err = MobileDiscoverModels(ctx, MobileModelDiscoveryRequest{Chat: mobileCreateDraft().Chat, ID: &snapshot.ID, Revision: &snapshot.Revision})
	mobileDiscoveryStatus(t, err, 504, "provider_models_timeout")
}

func TestMobileModelsDiscoveryTimeoutAndInvalidDraftNoWrite(t *testing.T) {
	mobileProviderHome(t)
	for _, input := range []string{"http://localhost:3430", "http://outside.example/v1", "https://user:key@outside.example/v1", "https://outside.example/v1?key=secret"} {
		if _, err := MobileDiscoverModels(context.Background(), MobileModelDiscoveryRequest{Chat: input}); !errors.Is(err, ErrMobileProviderInvalid) { t.Fatal("invalid discovery URL accepted") }
	}
	id := "missing"
	if _, err := MobileDiscoverModels(context.Background(), MobileModelDiscoveryRequest{Chat: "http://127.0.0.1:40000/v1", ID: &id}); !errors.Is(err, ErrMobileProviderInvalid) { t.Fatal("unpaired id/revision accepted") }
	var calls atomic.Int64
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { calls.Add(1); <-r.Context().Done() }))
	defer server.Close()
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Millisecond); defer cancel()
	_, err := MobileDiscoverModels(ctx, MobileModelDiscoveryRequest{Chat: server.URL+"/v1"})
	mobileDiscoveryStatus(t, err, 504, "provider_models_timeout")
	if calls.Load() > 1 { t.Fatal("timeout automatically replayed") }
	if _, err := os.Stat(Path()); !os.IsNotExist(err) { t.Fatal("discovery modified provider config") }
	transport := mobileDiscoveryTransport(); defer transport.CloseIdleConnections()
	if mobileModelsTimeout != 8*time.Second || mobileModelsBytes != 2<<20 || transport.Proxy != nil || transport.TLSClientConfig.InsecureSkipVerify || !transport.DisableCompression { t.Fatal("safe directory transport limits changed") }
}
