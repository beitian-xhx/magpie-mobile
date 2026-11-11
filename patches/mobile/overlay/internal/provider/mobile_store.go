package provider

import (
	"bytes"
	"crypto/hmac"
	"crypto/rand"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"net"
	"net/url"
	"os"
	"path/filepath"
	"reflect"
	"io"
	"regexp"
	"slices"
	"strconv"
	"strings"
	"sync"
	"unicode"
	"unicode/utf8"

	"github.com/tidwall/gjson"
	"github.com/tidwall/jsonc"
	"github.com/yetone/magpie/internal/catalog"
	"github.com/yetone/magpie/internal/edit"
	"github.com/yetone/magpie/internal/filelock"
	"github.com/yetone/magpie/internal/filememo"
	"github.com/yetone/magpie/internal/lastgood"
	"github.com/yetone/magpie/internal/mobilepolicy"
	"github.com/yetone/magpie/internal/steady"
)

var (
	ErrMobileProviderInvalid = errors.New("invalid mobile provider request")
	ErrMobileProviderRevision = errors.New("provider revision conflict")
	ErrMobileProviderExists = errors.New("provider already exists")
	ErrMobileProviderMissing = errors.New("provider not found")
	ErrMobileProviderUnsupported = errors.New("provider is not a supported custom API-key chat provider")
	ErrMobileProviderLocked = errors.New("provider lock unavailable")
	ErrMobileProviderWriteUnknown = errors.New("provider write outcome unknown")
	mobileProviderMu sync.Mutex
	mobileAcquireLock = filelock.Lock
	mobileWritePrivate = func(path string, raw []byte) error { return writePrivate(path, raw) }
	mobileRevisionKey = sync.OnceValues(func() ([]byte, error) {
		key := make([]byte, 32)
		_, err := rand.Read(key)
		return key, err
	})
	mobileCreateID = regexp.MustCompile("^[a-z0-9][a-z0-9-]{0,63}$")
	mobileExistingID = regexp.MustCompile("^[a-z0-9][a-z0-9._-]{0,63}$")
)

type MobileProviderKey struct {
	Set bool `json:"set"`
	Masked string `json:"masked"`
	Optional bool `json:"optional"`
}

type MobileProviderSnapshot struct {
	ID string `json:"id"`
	Revision string `json:"revision"`
	Name string `json:"name"`
	Chat string `json:"chat"`
	Models []string `json:"models"`
	NoModels bool `json:"noModels"`
	Key MobileProviderKey `json:"key"`
	ServerPin string `json:"serverPin,omitempty"`
}

type MobileProviderCreate struct {
	New bool `json:"new"`
	ID string `json:"id"`
	Name string `json:"name"`
	Chat string `json:"chat"`
	Key string `json:"key"`
	Models []string `json:"models"`
	NoModels bool `json:"noModels"`
	ServerPin string `json:"serverPin,omitempty"`
}

type MobileProviderEdit struct {
	ID string `json:"id"`
	Revision string `json:"revision"`
	Name *string `json:"name,omitempty"`
	Chat *string `json:"chat,omitempty"`
	Key *string `json:"key,omitempty"`
	Models *[]string `json:"models,omitempty"`
	NoModels *bool `json:"noModels,omitempty"`
	ServerPin *string `json:"serverPin,omitempty"`
}

func mobileText(s string, max int) (string, error) {
	s = strings.TrimSpace(s)
	if !utf8.ValidString(s) || utf8.RuneCountInString(s) < 1 || utf8.RuneCountInString(s) > max {
		return "", ErrMobileProviderInvalid
	}
	for _, c := range s {
		if unicode.IsControl(c) {
			return "", ErrMobileProviderInvalid
		}
	}
	return s, nil
}

func mobileServerPin(s string) (string, error) {
	s = strings.TrimSpace(s)
	if s == "" {
		return "", nil
	}
	if len(s) != 64 || strings.Trim(s, "0123456789abcdef") != "" {
		return "", ErrMobileProviderInvalid
	}
	return s, nil
}

func mobileChat(s string) (string, error) {
	s, err := mobileText(s, 4096)
	if err != nil {
		return "", err
	}
	u, err := url.Parse(s)
	if err != nil || u.Opaque != "" || u.Host == "" || u.User != nil || u.RawQuery != "" || u.ForceQuery || u.Fragment != "" || u.RawFragment != "" || u.RawPath != "" || strings.Contains(s, "#") || strings.HasSuffix(u.Host, ":") {
		return "", ErrMobileProviderInvalid
	}
	host := u.Hostname()
	if host == "" || strings.ContainsAny(host, " \t\r\n\\%") || strings.HasSuffix(host, ".") {
		return "", ErrMobileProviderInvalid
	}
	if port := u.Port(); port != "" {
		n, e := strconv.Atoi(port)
		if e != nil || n < 1 || n > 65535 {
			return "", ErrMobileProviderInvalid
		}
	}
	if u.Scheme == "http" {
		if (host != "127.0.0.1" && host != "::1") || u.Port() == "" {
			return "", ErrMobileProviderInvalid
		}
	} else if u.Scheme == "https" {
		// Only public literal IPs or external DNS names; no HTTPS private
		// addresses. No DNS/test/detect is performed by this metadata API.
		if ip := net.ParseIP(host); ip != nil {
			if !ip.IsGlobalUnicast() || ip.IsPrivate() || ip.IsLoopback() || ip.IsLinkLocalUnicast() {
				return "", ErrMobileProviderInvalid
			}
		} else {
			h := strings.ToLower(host)
			if h == "localhost" || !strings.Contains(h, ".") || strings.HasSuffix(h, ".localhost") || strings.HasSuffix(h, ".local") {
				return "", ErrMobileProviderInvalid
			}
			if len(h) > 253 { return "", ErrMobileProviderInvalid }
			for _, label := range strings.Split(h, ".") {
				if label == "" || len(label) > 63 || strings.HasPrefix(label, "-") || strings.HasSuffix(label, "-") { return "", ErrMobileProviderInvalid }
			}
			for _, c := range h {
				if !(c >= 'a' && c <= 'z' || c >= '0' && c <= '9' || c == '.' || c == '-') {
					return "", ErrMobileProviderInvalid
				}
			}
		}
	} else {
		return "", ErrMobileProviderInvalid
	}
	return strings.TrimRight(u.String(), "/"), nil
}

func mobileKey(s string) (string, error) {
	s = strings.TrimSpace(s)
	if len(s) < 1 || len(s) > 8192 {
		return "", ErrMobileProviderInvalid
	}
	for i := range s {
		if s[i] < '!' || s[i] > '~' {
			return "", ErrMobileProviderInvalid
		}
	}
	return s, nil
}

func mobileModels(models []string) ([]string, error) {
	if len(models) > 1000 {
		return nil, ErrMobileProviderInvalid
	}
	out := make([]string, 0, len(models))
	seen := map[string]bool{}
	for _, model := range models {
		m, err := mobileText(model, 256)
		if err != nil || seen[m] {
			return nil, ErrMobileProviderInvalid
		}
		seen[m] = true
		out = append(out, m)
	}
	return out, nil
}

func mobileSupported(p Provider) bool {
	_, err := mobileChat(p.Chat)
	return err == nil && p.Key != "" && p.Preset == "" && p.Vertex == nil && p.Account == nil && !p.IsPlugin() && !subscriptionID(p.ID) &&
		p.Responses == "" && p.Anthropic == "" && p.Gemini == "" && p.Decide == "" && (p.BaseAPI == "" || p.BaseAPI == "chat") && !p.InsecureTLS
}

func mobileTransaction(fn func() error) error {
	if !mobilepolicy.Enabled {
		return ErrMobileProviderUnsupported
	}
	mobileProviderMu.Lock()
	defer mobileProviderMu.Unlock()
	unlock, held := mobileAcquireLock(Path()+".lock", filelock.Wait)
	defer unlock()
	if !held {
		return ErrMobileProviderLocked // never filelock's permissive fallback
	}
	return fn()
}

func mobileCanonical() ([]byte, file, error) {
	raw, err := steady.ReadFile(Path())
	if errors.Is(err, os.ErrNotExist) {
		return nil, file{}, nil
	}
	if err != nil || len(raw) > 32<<20 {
		return nil, file{}, ErrUnreadable
	}
	var f file
	plain := jsonc.ToJSON(bytes.Clone(bytes.TrimPrefix(raw, utf8BOM)))
	if !mobileUnambiguousJSON(plain) || !gjson.ParseBytes(plain).IsObject() || !mobileCanonicalSpelling(plain) || json.Unmarshal(plain, &f) != nil {
		return nil, file{}, ErrUnreadable // no stale .bak or cached Find for edits
	}
	seen := map[string]bool{}
	for _, p := range f.Providers {
		if p.ID == "" || seen[p.ID] {
			return nil, file{}, ErrUnreadable
		}
		seen[p.ID] = true
	}
	f.mobileRevision = mobileFileRevision(raw)
	return raw, f, nil
}

func mobileSnapshot(p Provider, raw []byte) (MobileProviderSnapshot, error) {
	key, err := mobileRevisionKey()
	if err != nil {
		return MobileProviderSnapshot{}, ErrMobileProviderLocked
	}
	h := hmac.New(sha256.New, key)
	h.Write([]byte(Path()))
	h.Write([]byte{0})
	h.Write(raw)
	masked := ""
	if p.Key != "" {
		masked = "••••••••" // not even a short key can be returned in full
	}
	return MobileProviderSnapshot{p.ID, hex.EncodeToString(h.Sum(nil)), p.Name, p.Chat, append([]string{}, p.Models...), p.NoModels, MobileProviderKey{p.Key != "", masked, false}, p.ServerPin}, nil
}

func MobileEditableProvider(id string) (out MobileProviderSnapshot, err error) {
	if !mobileExistingID.MatchString(id) {
		return out, ErrMobileProviderInvalid
	}
	err = mobileTransaction(func() error {
		raw, f, e := mobileCanonical()
		if e != nil { return e }
		i := slices.IndexFunc(f.Providers, func(p Provider) bool { return p.ID == id })
		if i < 0 { return ErrMobileProviderMissing }
		if !mobileSupported(f.Providers[i]) { return ErrMobileProviderUnsupported }
		out, e = mobileSnapshot(f.Providers[i], raw)
		return e
	})
	return
}

func MobileCreateProvider(in MobileProviderCreate) (out MobileProviderSnapshot, err error) {
	if !in.New || !mobileCreateID.MatchString(in.ID) || in.ID == "magpie" || in.ID == "group" || in.ID == autoID || subscriptionID(in.ID) || in.Models == nil {
		return out, ErrMobileProviderInvalid
	}
	name, e := mobileText(in.Name, 120); if e != nil { return out, e }
	chat, e := mobileChat(in.Chat); if e != nil { return out, e }
	key, e := mobileKey(in.Key); if e != nil { return out, e }
	models, e := mobileModels(in.Models); if e != nil || in.NoModels != (len(models) == 0) { return out, ErrMobileProviderInvalid }
	pin, e := mobileServerPin(in.ServerPin); if e != nil { return out, e }
	p := Provider{ID: in.ID, Name: name, Chat: chat, Key: key, Models: models, NoModels: in.NoModels, BaseAPI: "chat", ServerPin: pin}
	err = mobileTransaction(func() error {
		raw, f, e := mobileCanonical(); if e != nil { return e }
		if slices.ContainsFunc(f.Providers, func(q Provider) bool { return q.ID == p.ID }) { return ErrMobileProviderExists }
		if raw == nil { raw = []byte("{\n  \"providers\": []\n}\n") }
		data, e := json.MarshalIndent(p, "  ", "  "); if e != nil { return ErrMobileProviderInvalid }
		updated, e := mobileAppendProvider(raw, data); if e != nil { return e }
		preview, e := mobileSnapshot(p, updated); if e != nil { return e }
		if e := mobileWrite(updated); e != nil { return e }
		out = preview
		return nil
	})
	return
}

func MobileEditProvider(in MobileProviderEdit) (out MobileProviderSnapshot, err error) {
	if !mobileExistingID.MatchString(in.ID) || len(in.Revision) != 64 || strings.Trim(in.Revision, "0123456789abcdef") != "" || in.NoModels != nil && in.Models == nil {
		return out, ErrMobileProviderInvalid
	}
	err = mobileTransaction(func() error {
		raw, f, e := mobileCanonical(); if e != nil { return e }
		i := slices.IndexFunc(f.Providers, func(p Provider) bool { return p.ID == in.ID })
		if i < 0 { return ErrMobileProviderMissing }
		p := f.Providers[i].clone() // latest complete canonical, never UI DTO
		if !mobileSupported(p) { return ErrMobileProviderUnsupported }
		before, e := mobileSnapshot(p, raw); if e != nil { return e }
		if !hmac.Equal([]byte(in.Revision), []byte(before.Revision)) { return ErrMobileProviderRevision }
		var fields []edit.KV
		if in.Name != nil { p.Name, e = mobileText(*in.Name, 120); if e != nil { return e }; fields = append(fields, edit.KV{Path: "name", Value: p.Name}) }
		if in.Chat != nil { p.Chat, e = mobileChat(*in.Chat); if e != nil { return e }; fields = append(fields, edit.KV{Path: "chat", Value: p.Chat}) }
		if in.ServerPin != nil { pin, e := mobileServerPin(*in.ServerPin); if e != nil { return e }; p.ServerPin = pin; fields = append(fields, edit.KV{Path: "serverPin", Value: pin}) }
		if in.Key != nil && strings.TrimSpace(*in.Key) != "" { p.Key, e = mobileKey(*in.Key); if e != nil { return e }; fields = append(fields, edit.KV{Path: "key", Value: p.Key}) }
		if in.Models != nil {
			p.Models, e = mobileModels(*in.Models); if e != nil { return e }
			p.NoModels = len(p.Models) == 0
			if in.NoModels != nil && *in.NoModels != p.NoModels { return ErrMobileProviderInvalid }
			fields = append(fields, edit.KV{Path: "models", Value: p.Models}, edit.KV{Path: "noModels", Value: p.NoModels})
		}
		updated, e := mobilePatchProvider(raw, i, fields, nil); if e != nil { return e }
		preview, e := mobileSnapshot(p, updated); if e != nil { return e }
		if e := mobileWrite(updated); e != nil { return e }
		out = preview
		return nil
	})
	return
}

func mobileRaw(raw []byte) ([]byte, []byte, bool) {
	base, marked := bytes.CutPrefix(raw, utf8BOM)
	return base, jsonc.ToJSONInPlace(bytes.Clone(base)), marked
}

func mobilePatchProvider(raw []byte, index int, fields []edit.KV, del []string) ([]byte, error) {
	if len(fields) == 0 && len(del) == 0 { return bytes.Clone(raw), nil }
	base, plain, marked := mobileRaw(raw)
	r := gjson.GetBytes(plain, "providers."+strconv.Itoa(index))
	if !r.IsObject() || r.Index < 0 || r.Index+len(r.Raw) > len(base) { return nil, ErrUnreadable }
	patched, err := edit.PatchJSON(base[r.Index:r.Index+len(r.Raw)], fields, del)
	if err != nil { return nil, ErrUnreadable }
	out := append(append(bytes.Clone(base[:r.Index]), patched...), base[r.Index+len(r.Raw):]...)
	if marked { out = append(bytes.Clone(utf8BOM), out...) }
	return out, nil
}

func mobileAppendProvider(raw, value []byte) ([]byte, error) {
	base, plain, marked := mobileRaw(raw)
	r := gjson.GetBytes(plain, "providers")
	if !r.Exists() || r.Type == gjson.Null {
		return edit.PatchJSON(raw, []edit.KV{{Path: "providers", Value: []json.RawMessage{json.RawMessage(value)}}}, nil)
	}
	if !r.IsArray() { return nil, ErrUnreadable }
	items := r.Array()
	at, prefix := r.Index+1, ""
	if len(items) > 0 {
		last := gjson.GetBytes(plain, "providers."+strconv.Itoa(len(items)-1))
		at, prefix = last.Index+len(last.Raw), ","
	}
	insert := append([]byte(prefix+"\n    "), value...)
	out := append(append(bytes.Clone(base[:at]), insert...), base[at:]...)
	if marked { out = append(bytes.Clone(utf8BOM), out...) }
	return out, nil
}

// JSON decoding and gjson byte offsets must describe the same unambiguous
// document. Reject duplicate/case-folded duplicate keys recursively, bounded
// to 64 nesting levels, rather than normalizing an abnormal existing file.
func mobileUnambiguousJSON(plain []byte) bool {
	if !utf8.Valid(plain) || len(plain) > 32<<20 { return false }
	dec := json.NewDecoder(bytes.NewReader(plain))
	dec.UseNumber()
	var value func(int) bool
	value = func(depth int) bool {
		if depth > 64 { return false }
		token, err := dec.Token(); if err != nil { return false }
		if delim, ok := token.(json.Delim); ok {
			switch delim {
			case '{':
				seen := map[string]bool{}
				for dec.More() {
					key, err := dec.Token(); if err != nil { return false }
					name, ok := key.(string); if !ok { return false }
					name = strings.ToLower(name)
					if seen[name] { return false }; seen[name] = true
					if !value(depth+1) { return false }
				}
				end, err := dec.Token(); return err == nil && end == json.Delim('}')
			case '[':
				for dec.More() { if !value(depth+1) { return false } }
				end, err := dec.Token(); return err == nil && end == json.Delim(']')
			default:
				return false
			}
		}
		return true
	}
	if !value(0) { return false }
	var extra any
	return dec.Decode(&extra) == io.EOF
}

// Go matches known struct tags case-insensitively, while gjson paths do not.
// A single "Providers"/"Name" alias would otherwise be read then patched under
// a different spelling. Keep unknown fields, but require known root/row tags.
func mobileCanonicalSpelling(plain []byte) bool {
	exact := func(object gjson.Result, kind reflect.Type) bool {
		known := map[string]string{}
		for i := 0; i < kind.NumField(); i++ {
			field := kind.Field(i)
			if field.PkgPath != "" { continue }
			name := strings.Split(field.Tag.Get("json"), ",")[0]
			if name == "-" { continue }; if name == "" { name = field.Name }
			known[strings.ToLower(name)] = name
		}
		ok := true
		object.ForEach(func(key, _ gjson.Result) bool {
			if name, found := known[strings.ToLower(key.String())]; found && key.String() != name { ok = false; return false }
			return true
		})
		return ok
	}
	root := gjson.ParseBytes(plain)
	if !exact(root, reflect.TypeOf(file{})) { return false }
	providers := root.Get("providers")
	if !providers.Exists() || providers.Type == gjson.Null { return true }
	if !providers.IsArray() { return false }
	for _, row := range providers.Array() {
		if !row.IsObject() || !exact(row, reflect.TypeOf(Provider{})) { return false }
	}
	return true
}

func mobileValidFile(raw []byte) bool {
	plain := jsonc.ToJSON(bytes.Clone(bytes.TrimPrefix(raw, utf8BOM)))
	var f file
	if !mobileUnambiguousJSON(plain) || !gjson.ParseBytes(plain).IsObject() || !mobileCanonicalSpelling(plain) || json.Unmarshal(plain, &f) != nil { return false }
	seen := map[string]bool{}
	for _, p := range f.Providers { if p.ID == "" || seen[p.ID] { return false }; seen[p.ID] = true }
	return true
}

func mobileWrite(raw []byte) error {
	if !mobileValidFile(raw) { return ErrUnreadable }
	if before, err := steady.ReadFile(Path()); err == nil && bytes.Equal(before, raw) { return nil }
	if err := os.MkdirAll(filepath.Dir(Path()), 0o700); err != nil { return ErrMobileProviderWriteUnknown }
	if err := lastgood.Keep(Path(), mobileValidFile, 0o600); err != nil { return ErrMobileProviderWriteUnknown }
	if err := mobileWritePrivate(Path(), raw); err != nil { return ErrMobileProviderWriteUnknown }
	filememo.Forget()
	catalog.Touched()
	return nil
}

// Every legacy mobile store uses the same strict lock and read-generation CAS.
// An obsolete background snapshot cannot overwrite a newer mobile API edit.
// Unknown provider fields/comments are patched around, not re-marshaled away.
func mobileStore(f file) (handled bool, err error) {
	if !mobilepolicy.Enabled { return false, nil }
	return true, mobileTransaction(func() error {
		raw, old, e := mobileCanonical(); if e != nil { return e }
		if f.mobileRevision != old.mobileRevision { return ErrMobileProviderRevision }
		if raw == nil { raw = []byte("{}\n") }
		if len(f.Providers) < len(old.Providers) { return ErrMobileProviderUnsupported }
		for i, p := range old.Providers {
			if f.Providers[i].ID != p.ID { return ErrMobileProviderUnsupported }
			set, del, e := mobileKnownDiff(p, f.Providers[i]); if e != nil { return e }
			raw, e = mobilePatchProvider(raw, i, set, del); if e != nil { return e }
		}
		for _, p := range f.Providers[len(old.Providers):] {
			v, e := json.Marshal(p); if e != nil { return ErrMobileProviderInvalid }
			raw, e = mobileAppendProvider(raw, v); if e != nil { return e }
		}
		// Non-provider fields use the original known schema; unchanged fields
		// (and unknown top-level fields) keep their exact bytes.
		f.Groups = slices.Clone(f.Groups)
		for i := range f.Groups { f.Groups[i] = f.Groups[i].stored() }
		old.Providers, f.Providers = nil, nil
		set, del, e := mobileKnownDiff(old, f); if e != nil { return e }
		raw, e = edit.PatchJSON(raw, set, del); if e != nil { return ErrUnreadable }
		return mobileWrite(raw)
	})
}

// A legacy known struct/map replacement could erase unknown nested members.
// Do not guess a merge: unchanged composite bytes stay; changed/deleted
// composites fail closed. Scalar values and scalar arrays (raw models) are safe.
func mobileUnsafeComposite(raw json.RawMessage) bool {
	var value any
	if json.Unmarshal(raw, &value) != nil { return false }
	switch v := value.(type) {
	case map[string]any:
		return true
	case []any:
		for _, element := range v {
			switch element.(type) { case map[string]any, []any: return true }
		}
	}
	return false
}

func mobileKnownDiff(old, next any) ([]edit.KV, []string, error) {
	x, e := json.Marshal(old); if e != nil { return nil, nil, ErrMobileProviderInvalid }
	y, e := json.Marshal(next); if e != nil { return nil, nil, ErrMobileProviderInvalid }
	var before, after map[string]json.RawMessage
	if json.Unmarshal(x, &before) != nil || json.Unmarshal(y, &after) != nil { return nil, nil, ErrMobileProviderInvalid }
	var set []edit.KV
	var del []string
	for key, value := range after {
		if !bytes.Equal(before[key], value) {
			if mobileUnsafeComposite(before[key]) || mobileUnsafeComposite(value) { return nil, nil, ErrMobileProviderUnsupported }
			set = append(set, edit.KV{Path: key, Value: value})
		}
	}
	for key, value := range before {
		if _, ok := after[key]; !ok {
			if mobileUnsafeComposite(value) { return nil, nil, ErrMobileProviderUnsupported }
			del = append(del, key)
		}
	}
	slices.SortFunc(set, func(a, b edit.KV) int { return strings.Compare(a.Path, b.Path) })
	slices.Sort(del)
	return set, del, nil
}
