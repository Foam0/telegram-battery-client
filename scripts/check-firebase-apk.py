#!/usr/bin/env python3
"""Verify the packaged Firebase resources, not just the source-set XML."""
import re
import subprocess
import sys
import xml.etree.ElementTree as ET
from pathlib import Path


def firebase_values(dump):
    values = {}
    for name, value in re.findall(
        r'^\s*resource 0x[0-9a-f]+ [^\s:]+:string/(\w+):[^\n]*\n\s*\(string(?:8|16)\) "([^"\n]*)"',
        dump, re.MULTILINE,
    ):
        values.setdefault(name, set()).add(value)
    return values


def main():
    apk, aapt = sys.argv[1:3]
    config = Path(__file__).resolve().parent.parent / 'TMessagesProj_App/src/hardened/res/values/battery_firebase.xml'
    expected = {node.attrib['name']: node.text for node in ET.parse(config).getroot().findall('string')}
    dump = subprocess.check_output([aapt, 'dump', '--values', 'resources', apk], text=True, encoding='utf-8', errors='replace')
    actual = firebase_values(dump)
    for name, value in expected.items():
        if actual.get(name) != {value}:
            raise SystemExit(f'Firebase resource mismatch: {name}')
    if 'tmessages2' in dump:
        raise SystemExit('Unexpected official Telegram Firebase project in APK')
    manifest = subprocess.check_output([aapt, 'dump', 'xmltree', apk, 'AndroidManifest.xml'], text=True, encoding='utf-8', errors='replace')
    for required in ('org.telegram.messenger.FcmPushListenerService', 'com.google.firebase.MESSAGING_EVENT',
                     'com.google.firebase.provider.FirebaseInitProvider'):
        if required not in manifest:
            raise SystemExit(f'Firebase manifest component missing: {required}')
    print('firebase_apk=ok (all client resources match the preserved hardened configuration)')


if __name__ == '__main__':
    main()
