package provider

import (
	"bytes"
	"context"
	"crypto/hmac"
	"crypto/tls"
	"crypto/x509"
	"encoding/base64"
	"encoding/json"
	"errors"
	"io"
	"net"
	"net/http"
	"net/url"
	"path"
	"slices"
	"strings"
	"time"

	"github.com/yetone/magpie/internal/mobilepolicy"
)

const mobileModelsTimeout = 8 * time.Second
const mobileModelsBytes = 2 << 20
const mobileModelsCount = 1000

type MobileModelDiscoveryRequest struct {
	Chat string `json:"chat"`
	Key *string `json:"key,omitempty"`
	ID *string `json:"id,omitempty"`
	Revision *string `json:"revision,omitempty"`
}

type MobileModelSummary struct {
	ID string `json:"id"`
	Name string `json:"name,omitempty"`
}

type MobileModelCatalog struct {
	Models []MobileModelSummary `json:"models"`
	BaseURL string `json:"baseUrl"`
}

// A fixed public error, never the supplier's body, URL, headers or credentials.
type MobileProviderModelsError struct {
	Status int
	Code string
	Message string
}

func (e *MobileProviderModelsError) Error() string { return e.Message }

func mobileModelsError(status int, code, message string) error {
	return &MobileProviderModelsError{status, code, message}
}

// Clone a validated stored key under the strict canonical lock, then release it
// before the network request. No Provider fields/revision are changed. An
// override key and chat are used for this read only and never sent back/saved.
func mobileDiscoveryKey(ctx context.Context, in MobileModelDiscoveryRequest) (string, error) {
	if (in.ID == nil) != (in.Revision == nil) { return "", ErrMobileProviderInvalid }
	key := ""
	if in.Key != nil && strings.TrimSpace(*in.Key) != "" {
		var err error
		key, err = mobileKey(*in.Key); if err != nil { return "", err }
	}
	if in.ID == nil { return key, nil }
	if !mobileExistingID.MatchString(*in.ID) || len(*in.Revision) != 64 || strings.Trim(*in.Revision, "0123456789abcdef") != "" { return "", ErrMobileProviderInvalid }
	err := mobileDiscoveryRead(ctx, func() error {
		raw, f, err := mobileCanonical(); if err != nil { return err }
		i := slices.IndexFunc(f.Providers, func(p Provider) bool { return p.ID == *in.ID })
		if i < 0 { return ErrMobileProviderMissing }
		p := f.Providers[i]
		if !mobileSupported(p) { return ErrMobileProviderUnsupported }
		snapshot, err := mobileSnapshot(p, raw); if err != nil { return err }
		if !hmac.Equal([]byte(*in.Revision), []byte(snapshot.Revision)) { return ErrMobileProviderRevision }
		if key == "" {
			oldOrigin, oldErr := mobileModelsOrigin(p.Chat)
			newOrigin, newErr := mobileModelsOrigin(in.Chat)
			if oldErr != nil || newErr != nil { return ErrMobileProviderInvalid }
			if oldOrigin != newOrigin { return mobileModelsError(400, "provider_models_origin_changed", "changing provider origin requires an explicit API key for this read") }
			key, err = mobileKey(p.Key)
		}
		return err
	})
	return key, err
}

// Lookup shares the canonical mutex/file lock but respects this read deadline.
// No request waits ten seconds for a CRUD lock then starts another eight-second
// vendor timeout. TryLock avoids an uncancellable queue on the process mutex.
func mobileDiscoveryRead(ctx context.Context, read func() error) error {
	for !mobileProviderMu.TryLock() {
		select { case <-ctx.Done(): return mobileModelsNetworkError(ctx, ctx.Err()); case <-time.After(5*time.Millisecond): }
	}
	defer mobileProviderMu.Unlock()
	if ctx.Err() != nil { return mobileModelsNetworkError(ctx, ctx.Err()) }
	wait := mobileModelsTimeout
	if deadline, ok := ctx.Deadline(); ok { wait = time.Until(deadline) }
	if wait <= 0 { return mobileModelsNetworkError(ctx, context.DeadlineExceeded) }
	unlock, held := mobileAcquireLock(Path()+".lock", wait)
	defer unlock()
	if ctx.Err() != nil { return mobileModelsNetworkError(ctx, ctx.Err()) }
	if !held { return ErrMobileProviderLocked }
	err := read()
	if ctx.Err() != nil { return mobileModelsNetworkError(ctx, ctx.Err()) }
	return err
}

func mobileModelsOrigin(chat string) (string, error) {
	base, err := mobileChat(chat); if err != nil { return "", err }
	u, err := url.Parse(base); if err != nil { return "", ErrMobileProviderInvalid }
	host := strings.ToLower(u.Hostname())
	if ip := net.ParseIP(host); ip != nil { host = ip.String() }
	port := u.Port()
	if port == "" { if u.Scheme == "https" { port = "443" } else { port = "80" } }
	return u.Scheme+"://"+net.JoinHostPort(host, port), nil
}

func mobileDiscoveryBases(chat string) ([]string, error) {
	base, err := mobileChat(chat); if err != nil { return nil, err }
	u, err := url.Parse(base); if err != nil { return nil, ErrMobileProviderInvalid }
	p := strings.TrimRight(u.Path, "/")
	if p != "" && path.Clean(p) != p { return nil, ErrMobileProviderInvalid }
	for _, suffix := range []string{"/chat/completions", "/responses", "/models"} {
		if strings.HasSuffix(p, suffix) { p = strings.TrimSuffix(p, suffix); break }
	}
	for strings.HasSuffix(p, "/v1/v1") { p = strings.TrimSuffix(p, "/v1") }
	u.Path = p
	first := strings.TrimRight(u.String(), "/")
	if strings.HasSuffix(p, "/v1") { u.Path = strings.TrimSuffix(p, "/v1") } else { u.Path = p+"/v1" }
	second := strings.TrimRight(u.String(), "/")
	if first == second { return []string{first}, nil }
	return []string{first, second}, nil
}

func mobileDiscoveryTransport() *http.Transport {
	return &http.Transport{
		Proxy: nil, // do not send draft credentials to environment HTTP proxies
		DialContext: (&net.Dialer{Timeout: mobileModelsTimeout}).DialContext,
		TLSClientConfig: &tls.Config{MinVersion: tls.VersionTLS12},
		TLSHandshakeTimeout: mobileModelsTimeout,
		ResponseHeaderTimeout: mobileModelsTimeout,
		MaxResponseHeaderBytes: 64 << 10,
		ForceAttemptHTTP2: true,
		DisableCompression: true,
	}
}

func MobileDiscoverModels(parent context.Context, in MobileModelDiscoveryRequest) (MobileModelCatalog, error) {
	if !mobilepolicy.Enabled { return MobileModelCatalog{}, ErrMobileProviderUnsupported }
	ctx, cancel := context.WithTimeout(parent, mobileModelsTimeout)
	defer cancel()
	bases, err := mobileDiscoveryBases(in.Chat); if err != nil { return MobileModelCatalog{}, err }
	key, err := mobileDiscoveryKey(ctx, in); if err != nil { return MobileModelCatalog{}, err }
	defer func() { key = ""; in.Key = nil }()
	for _, base := range bases { if mobileModelsContainKey(base, key) { return MobileModelCatalog{}, ErrMobileProviderInvalid } }
	transport := mobileDiscoveryTransport()
	defer transport.CloseIdleConnections()
	client := &http.Client{Transport: transport, Timeout: mobileModelsTimeout, CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }}
	for index, base := range bases {
		request, err := http.NewRequestWithContext(ctx, http.MethodGet, base+"/models", nil)
		if err != nil { return MobileModelCatalog{}, ErrMobileProviderInvalid }
		request.Header.Set("Accept", "application/json")
		request.Header.Set("Accept-Encoding", "identity")
		if key != "" { request.Header.Set("Authorization", "Bearer "+key) }
		response, err := client.Do(request)
		if err != nil { return MobileModelCatalog{}, mobileModelsNetworkError(ctx, err) }
		status := response.StatusCode
		if status == http.StatusNotFound && index+1 < len(bases) {
			response.Body.Close() // only an explicit same-origin 404 permits a second GET
			continue
		}
		if status >= 300 && status < 400 {
			response.Body.Close()
			return MobileModelCatalog{}, mobileModelsError(502, "provider_models_redirect_rejected", "model directory redirect was refused")
		}
		if status < 200 || status >= 300 {
			response.Body.Close()
			return MobileModelCatalog{}, mobileModelsStatusError(status)
		}
		if response.ContentLength > mobileModelsBytes {
			response.Body.Close()
			return MobileModelCatalog{}, mobileModelsError(502, "provider_models_too_large", "model directory exceeded its safe size limit")
		}
		raw, err := io.ReadAll(io.LimitReader(response.Body, mobileModelsBytes+1))
		response.Body.Close()
		if err != nil { return MobileModelCatalog{}, mobileModelsNetworkError(ctx, err) }
		if len(raw) > mobileModelsBytes { return MobileModelCatalog{}, mobileModelsError(502, "provider_models_too_large", "model directory exceeded its safe size limit") }
		models, err := mobileParseModels(raw, key)
		if err != nil { return MobileModelCatalog{}, err }
		return MobileModelCatalog{models, base}, nil
	}
	return MobileModelCatalog{}, mobileModelsStatusError(http.StatusNotFound)
}

func mobileModelsNetworkError(ctx context.Context, err error) error {
	var timeout net.Error
	if ctx.Err() != nil || errors.As(err, &timeout) && timeout.Timeout() {
		return mobileModelsError(504, "provider_models_timeout", "model directory read timed out")
	}
	var dns *net.DNSError
	if errors.As(err, &dns) { return mobileModelsError(502, "provider_models_dns", "system DNS could not resolve the model directory") }
	var verification *tls.CertificateVerificationError
	var authority x509.UnknownAuthorityError
	var invalid x509.CertificateInvalidError
	var hostname x509.HostnameError
	var roots x509.SystemRootsError
	if errors.As(err, &verification) || errors.As(err, &authority) || errors.As(err, &invalid) || errors.As(err, &hostname) || errors.As(err, &roots) {
		return mobileModelsError(502, "provider_models_tls_certificate", "system TLS certificate verification failed")
	}
	return mobileModelsError(502, "provider_models_unavailable", "model directory connection failed")
}

func mobileModelsStatusError(status int) error {
	switch status {
	case 401:
		return mobileModelsError(401, "provider_models_key_required", "model directory requires a valid API key")
	case 403:
		return mobileModelsError(403, "provider_models_forbidden", "model directory access was refused")
	case 404:
		return mobileModelsError(404, "provider_models_not_found", "model directory was not found")
	case 429:
		return mobileModelsError(429, "provider_models_rate_limited", "model directory read was rate limited")
	}
	if status < 400 || status > 599 { status = 502 }
	return mobileModelsError(status, "provider_models_upstream_error", "supplier refused the model directory read")
}

func mobileModelsContainKey(value, key string) bool {
	if key == "" { return false }
	variants := []string{key, url.QueryEscape(key), url.PathEscape(key), base64.StdEncoding.EncodeToString([]byte(key))}
	for _, secret := range variants { if secret != "" && strings.Contains(strings.ToLower(value), strings.ToLower(secret)) { return true } }
	return false
}

func mobileParseModels(raw []byte, key string) ([]MobileModelSummary, error) {
	invalid := func() error { return mobileModelsError(502, "provider_models_invalid", "supplier returned an invalid model directory") }
	if !mobileUnambiguousJSON(raw) { return nil, invalid() }
	var envelope map[string]json.RawMessage
	if json.Unmarshal(raw, &envelope) != nil { return nil, invalid() }
	data, ok := envelope["data"]
	if !ok || !bytes.HasPrefix(bytes.TrimSpace(data), []byte("[")) { return nil, invalid() }
	var rows []map[string]json.RawMessage
	if json.Unmarshal(data, &rows) != nil || len(rows) > mobileModelsCount { return nil, invalid() }
	models := make([]MobileModelSummary, 0, len(rows))
	seen := map[string]bool{}
	for _, row := range rows {
		var id, name string
		if json.Unmarshal(row["id"], &id) != nil { return nil, invalid() }
		if value, ok := row["name"]; ok && json.Unmarshal(value, &name) != nil { return nil, invalid() }
		var err error
		id, err = mobileText(id, 256); if err != nil { return nil, invalid() }
		name = strings.TrimSpace(name)
		if name != "" { name, err = mobileText(name, 120); if err != nil { return nil, invalid() } }
		if mobileModelsContainKey(id, key) || mobileModelsContainKey(name, key) { return nil, mobileModelsError(502, "provider_models_sensitive_content", "supplier model directory contained sensitive content") }
		if !seen[id] { models = append(models, MobileModelSummary{id, name}); seen[id] = true }
	}
	return models, nil
}
