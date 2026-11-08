#!/usr/bin/env python3
"""Static guards, not a substitute for Android runtime network validation."""
from pathlib import Path
workflow=Path('.github/workflows/build.yml').read_text()
step=workflow.split('- name: Build the Android ARM64 mobile core',1)[1].split('- name:',1)[0]
assert "CGO_ENABLED: '1'" in step, 'Android core requires Bionic/cgo DNS'
assert 'aarch64-linux-android29-clang' in step and '-linkmode=external' in step
service=Path('app/src/main/java/cc/fplj/magpiemobile/GatewayService.kt').read_text()
assert 'SSL_CERT_DIR' in service
assert '/apex/com.android.conscrypt/cacerts:/system/etc/security/cacerts' in service
source=Path('patches/mobile/overlay/internal/provider/mobile_discovery.go').read_text()
assert 'InsecureSkipVerify: true' not in source and 'MinVersion: tls.VersionTLS12' in source
print('PASS: Android Bionic DNS, system/APEX trust directories, TLS verification retained')
