#!/usr/bin/env python3
"""Verify a signed release APK before distribution; never requires the private key."""
import argparse
import hashlib
from pathlib import Path
import re
import subprocess


def verify(apk, sdk, certificate, version):
    tools = sdk / 'build-tools/36.0.0'
    result = subprocess.run([str(tools/'apksigner'), 'verify', '--verbose', '--print-certs', str(apk)],
                            check=True, capture_output=True, text=True)
    fingerprints = re.findall(r'Signer #\d+ certificate SHA-256 digest: ([0-9a-f]+)', result.stdout)
    if fingerprints != [certificate]:
        raise ValueError('Unexpected APK signing certificate')
    info = subprocess.check_output([str(tools/'aapt2'), 'dump', 'badging', str(apk)], text=True)
    if not re.search(r"package: name='org\.teslatempo' versionCode='\d+' versionName='" + re.escape(version) + "'", info):
        raise ValueError('Unexpected package or version')
    if 'application-debuggable' in info or not re.search(r"(?:minSdkVersion|sdkVersion):'33'", info) or "targetSdkVersion:'36'" not in info:
        raise ValueError('Unexpected Android release configuration')
    permissions = set(re.findall(r"uses-permission: name='([^']+)'", info))
    allowed = {'android.permission.INTERNET', 'android.permission.POST_NOTIFICATIONS',
               'android.permission.RECEIVE_BOOT_COMPLETED', 'org.teslatempo.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION'}
    if not permissions <= allowed:
        raise ValueError('Unexpected APK permissions')
    return hashlib.sha256(apk.read_bytes()).hexdigest()


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('apk', type=Path)
    parser.add_argument('--sdk', type=Path, required=True)
    parser.add_argument('--certificate', required=True)
    parser.add_argument('--version', required=True)
    args = parser.parse_args()
    print(verify(args.apk, args.sdk, args.certificate, args.version), args.apk.name)
