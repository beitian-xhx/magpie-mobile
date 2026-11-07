//go:build mobile

package provider

import (
	"bytes"
	"encoding/json"
	"errors"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/tidwall/gjson"
)

func mobileProviderHome(t *testing.T) {
	t.Helper()
	t.Setenv("XDG_CONFIG_HOME", t.TempDir())
	if err := os.MkdirAll(filepath.Dir(Path()), 0o700); err != nil { t.Fatal(err) }
}

func mobileCreateDraft() MobileProviderCreate {
	return MobileProviderCreate{New: true, ID: "mobile-local", Name: "Local API", Chat: "http://127.0.0.1:48763/v1", Key: "local-synthetic-key", Models: []string{"raw-model"}, NoModels: false}
}

func TestMobileProviderCreateIsLocalMetadataOnly(t *testing.T) {
	mobileProviderHome(t)
	// No server is running at this address: even probing /models would fail.
	draft := mobileCreateDraft()
	snapshot, err := MobileCreateProvider(draft)
	if err != nil || snapshot.ID != draft.ID || len(snapshot.Revision) != 64 || !snapshot.Key.Set || snapshot.Key.Optional { t.Fatal("create failed or snapshot contract differs", err) }
	encoded, _ := json.Marshal(snapshot)
	if bytes.Contains(encoded, []byte(draft.Key)) { t.Fatal("snapshot exposed full key") }
	if _, err := MobileCreateProvider(draft); !errors.Is(err, ErrMobileProviderExists) { t.Fatal("duplicate creation did not conflict") }
	st, err := os.Stat(Path())
	if err != nil || st.Mode().Perm() != 0o600 { t.Fatal("provider file is not private") }
	raw, f, err := mobileCanonical()
	if err != nil || len(f.Providers) != 1 || f.Providers[0].Chat != draft.Chat || f.Providers[0].Key != draft.Key || !mobileValidFile(raw) { t.Fatal("canonical create failed") }
}

func TestMobileProviderEditPreservesCanonicalAndComments(t *testing.T) {
	mobileProviderHome(t)
	fixture := `{
 // top-level notes retained
 "providers": [{
  "id":"mobile-existing", "name":"Before", "chat":"http://127.0.0.1:48763/v1", "key":"private-existing-key",
  // advanced settings remain byte-for-byte
  "headers":{"X-Private":"sensitive-header"}, "proxy":"http://127.0.0.1:7890",
  "maxConcurrency":3, "queueLimit":9,
  "keys":[{"name":"other","key":"other-private-key","futureNested":{"keep":"opaque"}}], "fallback":["other/raw"],
  "futureUnsupported":{"opaque":[1,2,3]}, "models":[], "noModels":false,
 }],
 "futureTopLevel":{"doNotLose":true},
}`
	if err := os.WriteFile(Path(), []byte(fixture), 0o600); err != nil { t.Fatal(err) }
	// The real patched read path, not only the new API, accepts retained JSONC.
	legacy, err := read(); if err != nil || len(legacy.Providers) != 1 { t.Fatal("mobile read cannot consume JSONC") }
	before, err := MobileEditableProvider("mobile-existing"); if err != nil { t.Fatal(err) }
	if before.Models == nil || before.NoModels { t.Fatal("legacy Select-all snapshot lost []/false semantics") }
	name, blank := "After", ""
	out, err := MobileEditProvider(MobileProviderEdit{ID: before.ID, Revision: before.Revision, Name: &name, Key: &blank})
	if err != nil || out.Name != name || out.NoModels || len(out.Models) != 0 { t.Fatal("safe edit failed", err) }
	raw, f, err := mobileCanonical(); if err != nil { t.Fatal(err) }
	p := f.Providers[0]
	if p.Key != "private-existing-key" || p.Headers["X-Private"] != "sensitive-header" || p.Proxy != "http://127.0.0.1:7890" || p.MaxConcurrency == nil || *p.MaxConcurrency != 3 || p.QueueLimit != 9 || len(p.Keys) != 1 { t.Fatal("unsupported canonical fields were zeroed") }
	plain := mobileProviderJSON(raw)
	if gjson.GetBytes(plain, "providers.0.futureUnsupported").Raw != `{"opaque":[1,2,3]}` || !gjson.GetBytes(plain, "futureTopLevel.doNotLose").Bool() || gjson.GetBytes(plain, "providers.0.keys.0.futureNested.keep").String() != "opaque" { t.Fatal("unknown fields were lost") }
	for _, comment := range []string{"// top-level notes retained", "// advanced settings remain byte-for-byte"} { if !bytes.Contains(raw, []byte(comment)) { t.Fatal("comments were lost") } }
	// An old background read cannot restore Before or clear the newer data.
	legacy.Providers[0].QueueLimit = 123
	if err := store(legacy); !errors.Is(err, ErrMobileProviderRevision) { t.Fatal("stale legacy store overwrote newer API edit", err) }
	unchanged, _ := os.ReadFile(Path()); if !bytes.Equal(unchanged, raw) { t.Fatal("conflict changed file") }
	// A fresh background update patches its changed known field only.
	fresh, err := read(); if err != nil { t.Fatal(err) }; fresh.Providers[0].QueueLimit = 10
	if err := store(fresh); err != nil { t.Fatal("fresh legacy store failed", err) }
	again, _, err := mobileCanonical(); if err != nil || !bytes.Contains(again, []byte("futureUnsupported")) || !bytes.Contains(again, []byte("// advanced settings")) { t.Fatal("legacy store lost unsupported raw data") }
	current, err := MobileEditableProvider(before.ID); if err != nil { t.Fatal(err) }
	chat, replacement := "https://api.example.com/v1", "new-primary-private-key"
	if _, err := MobileEditProvider(MobileProviderEdit{ID: current.ID, Revision: current.Revision, Chat: &chat, Key: &replacement}); err != nil { t.Fatal("chat/key edit failed", err) }
	stable, canonical, err := mobileCanonical(); if err != nil { t.Fatal(err) }
	if gjson.GetBytes(mobileProviderJSON(stable), "providers.0.keys.0.futureNested.keep").String() != "opaque" { t.Fatal("primitive edit lost unknown nested struct member") }
	canonical.Providers[0].Keys = nil // a whole known composite deletion would erase futureNested
	if err := store(canonical); !errors.Is(err, ErrMobileProviderUnsupported) { t.Fatal("unsafe legacy composite replacement was not rejected") }
	final, _ := os.ReadFile(Path()); if !bytes.Equal(final, stable) { t.Fatal("rejected composite edit changed file") }
}

func TestMobileProviderRevisionConflictAndExplicitNoModels(t *testing.T) {
	mobileProviderHome(t)
	snapshot, err := MobileCreateProvider(mobileCreateDraft()); if err != nil { t.Fatal(err) }
	name := "changed"
	updated, err := MobileEditProvider(MobileProviderEdit{ID: snapshot.ID, Revision: snapshot.Revision, Name: &name}); if err != nil { t.Fatal(err) }
	if _, err := MobileEditProvider(MobileProviderEdit{ID: snapshot.ID, Revision: snapshot.Revision, Name: &name}); !errors.Is(err, ErrMobileProviderRevision) { t.Fatal("old revision accepted") }
	models := []string{}
	none := true
	updated, err = MobileEditProvider(MobileProviderEdit{ID: updated.ID, Revision: updated.Revision, Models: &models, NoModels: &none})
	if err != nil || !updated.NoModels || updated.Models == nil || len(updated.Models) != 0 { t.Fatal("explicit empty models did not select none") }
	keyOnly := "replacement-key"
	stillNone, err := MobileEditProvider(MobileProviderEdit{ID: updated.ID, Revision: updated.Revision, Key: &keyOnly})
	if err != nil || !stillNone.NoModels { t.Fatal("key-only edit changed model picks") }
}

func TestMobileProviderBadURLsAndFieldsPerformNoWrite(t *testing.T) {
	mobileProviderHome(t)
	for _, chat := range []string{"file:///etc/passwd", "http://example.com/v1", "http://localhost:3425/v1", "http://127.0.0.1/v1", "http://127.0.0.2:4000/v1", "https://user:password@example.com/v1", "https://example.com/v1?key=secret", "https://example.com/v1#secret", "https://127.0.0.1/v1", "https://192.168.1.2/v1", "https://example.com:/v1", "https://api..example.com/v1"} {
		draft := mobileCreateDraft(); draft.Chat = chat
		if _, err := MobileCreateProvider(draft); !errors.Is(err, ErrMobileProviderInvalid) { t.Fatal("bad URL accepted") }
		if _, err := os.Stat(Path()); !errors.Is(err, os.ErrNotExist) { t.Fatal("bad request wrote provider file") }
	}
	for _, key := range []string{"", "two keys", "key\nother", strings.Repeat("a", 8193)} {
		draft := mobileCreateDraft(); draft.Key = key
		if _, err := MobileCreateProvider(draft); !errors.Is(err, ErrMobileProviderInvalid) { t.Fatal("invalid key accepted") }
	}
	draft := mobileCreateDraft(); draft.Models = []string{"duplicate", "duplicate"}
	if _, err := MobileCreateProvider(draft); !errors.Is(err, ErrMobileProviderInvalid) { t.Fatal("duplicate models accepted") }
	draft = mobileCreateDraft(); draft.ID = "new.with-dot"
	if _, err := MobileCreateProvider(draft); !errors.Is(err, ErrMobileProviderInvalid) { t.Fatal("new non-slug id accepted") }
}

func TestMobileProviderStrictLockAndUnknownWriteNeverReplay(t *testing.T) {
	mobileProviderHome(t)
	lock := mobileAcquireLock
	mobileAcquireLock = func(string, time.Duration) (func(), bool) { return func() {}, false }
	_, err := MobileCreateProvider(mobileCreateDraft())
	mobileAcquireLock = lock
	if !errors.Is(err, ErrMobileProviderLocked) { t.Fatal("lock failure fell back to unguarded write") }
	if _, err := os.Stat(Path()); !errors.Is(err, os.ErrNotExist) { t.Fatal("no-lock path wrote") }
	writer := mobileWritePrivate
	defer func() { mobileWritePrivate = writer; mobileAcquireLock = lock }()
	calls := 0
	mobileWritePrivate = func(path string, raw []byte) error {
		calls++
		if err := writer(path, raw); err != nil { return err }
		return errors.New("synthetic error after write")
	}
	if _, err := MobileCreateProvider(mobileCreateDraft()); !errors.Is(err, ErrMobileProviderWriteUnknown) { t.Fatal("write uncertainty not reported") }
	if calls != 1 { t.Fatal("uncertain write was automatically replayed") }
	if _, err := MobileCreateProvider(mobileCreateDraft()); !errors.Is(err, ErrMobileProviderExists) || calls != 1 { t.Fatal("explicit duplicate attempt wrote again") }
}

func TestMobileProviderCanonicalDuplicateKeysFailClosed(t *testing.T) {
	mobileProviderHome(t)
	cases := []string{
		`{"providers":[],"providers":[]}`,
		`{"providers":[{"id":"first","id":"second"}]}`,
		`{"providers":[{"id":"first","ID":"second"}]}`,
		`{"Providers":[]}`,
		`{"providers":[{"id":"one","Name":"noncanonical"}]}`,
		`{"providers":[],"future":{"x":1,"x":2}}`,
		`{"providers":[],"future":` + strings.Repeat("[", 70) + "0" + strings.Repeat("]", 70) + "}",
	}
	for _, fixture := range cases {
		if err := os.WriteFile(Path(), []byte(fixture), 0o600); err != nil { t.Fatal(err) }
		if mobileValidFile([]byte(fixture)) { t.Fatal("ambiguous/deep canonical accepted") }
		if _, err := MobileCreateProvider(mobileCreateDraft()); !errors.Is(err, ErrUnreadable) { t.Fatal("ambiguous canonical allowed mutation") }
		if _, err := MobileEditableProvider("one"); !errors.Is(err, ErrUnreadable) { t.Fatal("ambiguous canonical allowed snapshot") }
		raw, _ := os.ReadFile(Path()); if string(raw) != fixture { t.Fatal("ambiguous file normalized or overwritten") }
	}
}

func TestMobileProviderUnsupportedSnapshotAndCorruptionFailClosed(t *testing.T) {
	mobileProviderHome(t)
	fixture := `{"providers":[{"id":"preset-only","name":"Preset","preset":"openai","chat":"https://api.example.com/v1","key":"private"}]}`
	if err := os.WriteFile(Path(), []byte(fixture), 0o600); err != nil { t.Fatal(err) }
	if _, err := MobileEditableProvider("preset-only"); !errors.Is(err, ErrMobileProviderUnsupported) { t.Fatal("unsupported provider became editable") }
	if err := os.WriteFile(Path(), []byte("{corrupt"), 0o600); err != nil { t.Fatal(err) }
	if _, err := MobileCreateProvider(mobileCreateDraft()); !errors.Is(err, ErrUnreadable) { t.Fatal("corrupt file treated as empty") }
	raw, _ := os.ReadFile(Path()); if string(raw) != "{corrupt" { t.Fatal("corrupt file overwritten") }
}
