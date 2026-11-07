#!/usr/bin/env python3
"""Static UX guard: keyboard focus is not a requirement for automatic discovery."""
from pathlib import Path
p=Path('app/src/main/java/cc/fplj/magpiemobile/ProvidersPanel.kt')
s=p.read_text()
assert 'keyFocused' not in s, 'Key focus must not suppress or re-arm discovery'
assert 'delay(1000)' in s, 'Settle input before automatic metadata GET'
assert 'ProviderModelDiscoveryRequest(baseUrl, key,' in s, 'Use current draft, not saved gateway catalog'
assert 'discoverModels(activeRequest)' in s, 'Automatic trigger must call read-only discovery'
assert 'keyFingerprint' in s and 'catalogSequence' in s, 'Bind late responses to current credentials/generation'
print('PASS: automatic model discovery does not wait for password-field blur')
