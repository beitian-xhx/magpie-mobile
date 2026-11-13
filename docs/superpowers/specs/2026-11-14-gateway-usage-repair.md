# Android gateway and usage repair

User authorization: continue fixing gateway stability/latency and restore desktop usage functionality, retaining the existing monochrome Routing/Usage two-page application.

## Evidence and scope

The bundled WorkBuddy 0.2.33 plugin awaits remote /v3/config before chats that omit reasoning effort, and injects the vendor's default effort (potentially high). Android should preserve explicit effort and otherwise leave the upstream request's effort unset. Guard this behavior with MAGPIE_MOBILE=1, injected into the actual native process environment; desktop behavior stays intact. Verify through the real bundled plugin loader with fake credentials and intercepted fetches, measuring before and after and checking outbound requests. Do not change DSH Go's timeout to disguise waiting.

The existing usage parser does not read the upstream ledger's `rows`, `ms`, `in`, `out` and cache fields. Its generic JSON number walker is not a usable dashboard. Replace the minimal dashboard's usage content with schema-specific totals, trend, grouping, quota windows/history and paginated filtered ledger. Queries use the same selected period and device time zone. Local summary/ledger and vendor quota endpoints load independently with observable errors; stale responses must not overwrite a newer query or disconnected session. Keep prior readings only within the same query and label errors. Quota retrieval has its own timeout longer than the core's 12 second budget.

The current self-test treats a role-only event as a reply and closes the stream early. Replace it with a bounded full SSE probe that measures actual text/reasoning/tool content, requires a finish marker, reports stream errors, exposes cancellation and lets the user choose the model. Results cannot prove another app is at fault. No automatic chargeable probes.

Keep the foreground gateway awake while explicitly running and release that ownership on stop/destruction. This is a background reliability improvement; real arm64 screen-off validation remains required because the local x86 emulator cannot run the native core.

## Acceptance

- Fixture shaped exactly like upstream usage API produces nonzero correct counts/tokens/timing, supports period/filter/page requests, and rejects malformed responses instead of claiming zero usage.
- One failed or slow remote quota read does not delay local usage. Switching query/disconnecting suppresses stale results.
- Quota bars distinguish unlimited, text-only, balance and bounded percentage windows; timestamps/errors remain visible. Charts have textual values and labels.
- WorkBuddy mobile chats skip implicit config lookup/high effort while explicit effort and free-only checks remain honored. Desktop behavior tested unchanged.
- SSE probe ignores comments and role-only events, detects errors/early EOF, and waits through completion.
- Relevant JVM/app tests and CI Android build/signature/payload verification pass. Deliver signed update APK with checksums and a truthful verification record.
