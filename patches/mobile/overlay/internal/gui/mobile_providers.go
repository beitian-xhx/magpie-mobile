package gui

import (
	"bytes"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"regexp"
	"strings"
	"unicode/utf8"

	"github.com/yetone/magpie/internal/provider"
)

var mobileProviderPathID = regexp.MustCompile("^[a-z0-9][a-z0-9._-]{0,63}$")

func mobileProviderAPIAllowed(method, p string) bool {
	if method == http.MethodPost {
		return p == "/api/mobile/providers/create" || p == "/api/mobile/providers/edit" || p == "/api/mobile/providers/models"
	}
	if method == http.MethodGet {
		id, ok := strings.CutPrefix(p, "/api/mobile/providers/editable/")
		return ok && mobileProviderPathID.MatchString(id)
	}
	return false
}

// The wrapper is inside webGuard/webOwnPage, before any desktop route. It
// performs a bounded GET directory read or one explicit core mutation; never
// test/detect, vendor inference, generic model fetch or automatic replay.
func mobileProviderServe(w http.ResponseWriter, r *http.Request) bool {
	if !mobileProviderAPIAllowed(r.Method, r.URL.Path) { return false }
	w.Header().Set("Cache-Control", "no-store")
	var snapshot provider.MobileProviderSnapshot
	var err error
	switch r.URL.Path {
	case "/api/mobile/providers/models":
		var in provider.MobileModelDiscoveryRequest
		err = mobileProviderDecode(w, r, &in, []string{"chat", "key", "id", "revision"})
		if err != nil { mobileProviderFailure(w, err); return true }
		catalog, err := provider.MobileDiscoverModels(r.Context(), in)
		if err != nil { mobileProviderFailure(w, err); return true }
		writeJSON(w, catalog)
		return true
	case "/api/mobile/providers/create":
		var in provider.MobileProviderCreate
		err = mobileProviderDecode(w, r, &in, []string{"new", "id", "name", "chat", "key", "models", "noModels", "serverPin"})
		if err == nil { snapshot, err = provider.MobileCreateProvider(in) }
	case "/api/mobile/providers/edit":
		var in provider.MobileProviderEdit
		err = mobileProviderDecode(w, r, &in, []string{"id", "revision", "name", "chat", "key", "models", "noModels", "serverPin"})
		if err == nil { snapshot, err = provider.MobileEditProvider(in) }
	default:
		id, _ := strings.CutPrefix(r.URL.Path, "/api/mobile/providers/editable/")
		snapshot, err = provider.MobileEditableProvider(id)
	}
	if err != nil { mobileProviderFailure(w, err); return true }
	writeJSON(w, snapshot)
	return true
}

// Go's field matching is otherwise case-insensitive and silently accepts
// duplicate keys. Exact allowlists, duplicates, null, trailing JSON, invalid
// UTF-8 and oversized requests are rejected before the provider transaction.
func mobileProviderDecode(w http.ResponseWriter, r *http.Request, out any, allowed []string) error {
	r.Body = http.MaxBytesReader(w, r.Body, 512<<10)
	raw, err := io.ReadAll(r.Body)
	if err != nil || !utf8.Valid(raw) { return provider.ErrMobileProviderInvalid }
	dec := json.NewDecoder(bytes.NewReader(raw))
	open, err := dec.Token()
	if err != nil || open != json.Delim('{') { return provider.ErrMobileProviderInvalid }
	seen := map[string]bool{}
	known := map[string]bool{}
	for _, name := range allowed { known[name] = true }
	for dec.More() {
		token, e := dec.Token(); if e != nil { return provider.ErrMobileProviderInvalid }
		name, ok := token.(string)
		if !ok || seen[name] || !known[name] { return provider.ErrMobileProviderInvalid }
		seen[name] = true
		var value json.RawMessage
		if dec.Decode(&value) != nil || bytes.Equal(bytes.TrimSpace(value), []byte("null")) { return provider.ErrMobileProviderInvalid }
	}
	if close, e := dec.Token(); e != nil || close != json.Delim('}') { return provider.ErrMobileProviderInvalid }
	var extra any
	if dec.Decode(&extra) != io.EOF { return provider.ErrMobileProviderInvalid }
	strict := json.NewDecoder(bytes.NewReader(raw))
	strict.DisallowUnknownFields()
	if strict.Decode(out) != nil { return provider.ErrMobileProviderInvalid }
	return nil
}

func mobileProviderFailure(w http.ResponseWriter, err error) {
	status, code, message := http.StatusInternalServerError, "provider_unreadable", "provider configuration cannot be safely read"
	var discovery *provider.MobileProviderModelsError
	switch {
	case errors.As(err, &discovery):
		status, code, message = discovery.Status, discovery.Code, discovery.Message
		w.Header().Set("X-Magpie-Mobile-Error-Code", discovery.Code)
	case errors.Is(err, provider.ErrMobileProviderRevision):
		status, code, message = http.StatusConflict, "provider_revision_conflict", "provider changed; reload editable snapshot"
	case errors.Is(err, provider.ErrMobileProviderExists):
		status, code, message = http.StatusConflict, "provider_already_exists", "provider id already exists"
	case errors.Is(err, provider.ErrMobileProviderMissing):
		status, code, message = http.StatusNotFound, "provider_not_found", "provider not found"
	case errors.Is(err, provider.ErrMobileProviderUnsupported):
		status, code, message = http.StatusUnprocessableEntity, "provider_unsupported", "only custom API-key OpenAI chat providers can be edited"
	case errors.Is(err, provider.ErrMobileProviderInvalid):
		status, code, message = http.StatusBadRequest, "provider_invalid_request", "invalid provider fields or URL"
	case errors.Is(err, provider.ErrMobileProviderLocked):
		status, code, message = http.StatusServiceUnavailable, "provider_locked", "provider lock unavailable; no write performed"
	case errors.Is(err, provider.ErrMobileProviderWriteUnknown):
		status, code, message = http.StatusServiceUnavailable, "provider_write_unknown", "provider write outcome unknown; read state before another user-requested write"
	}
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(map[string]any{"error": map[string]string{"code": code, "message": message}})
}
