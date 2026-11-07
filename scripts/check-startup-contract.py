#!/usr/bin/env python3
"""Static guards for the actual manifest/security XML (not an Android runtime test)."""
from pathlib import Path
import xml.etree.ElementTree as ET
root=Path(__file__).resolve().parents[1]
a='{http://schemas.android.com/apk/res/android}'
m=ET.parse(root/'app/src/main/AndroidManifest.xml').getroot()
permissions={p.attrib[a+'name'] for p in m.findall('uses-permission')}
assert {'android.permission.INTERNET','android.permission.FOREGROUND_SERVICE',
        'android.permission.FOREGROUND_SERVICE_SPECIAL_USE','android.permission.POST_NOTIFICATIONS'} <= permissions
app=m.find('application')
assert app.attrib[a+'allowBackup']=='false'
assert app.attrib[a+'networkSecurityConfig']=='@xml/network_security_config'
service=next(s for s in app.findall('service') if s.attrib[a+'name']=='.GatewayService')
assert service.attrib[a+'exported']=='false'
assert service.attrib[a+'foregroundServiceType']=='specialUse'
assert any(p.attrib[a+'name']=='android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE' for p in service.findall('property'))
net=ET.parse(root/'app/src/main/res/xml/network_security_config.xml').getroot()
assert net.find('base-config').attrib['cleartextTrafficPermitted']=='false'
allowed=net.find('domain-config')
assert allowed.attrib['cleartextTrafficPermitted']=='true'
assert {d.text for d in allowed.findall('domain')}=={'127.0.0.1','localhost'}
assert all(d.attrib['includeSubdomains']=='false' for d in allowed.findall('domain'))
print('PASS: actual FGS manifest, private service, backup disabled, loopback-only HTTP domains')
