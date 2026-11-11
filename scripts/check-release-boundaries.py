#!/usr/bin/env python3
"""Reject accidental private artifacts in tracked product files before publishing."""
from pathlib import PurePosixPath, Path
import re
import subprocess

files = subprocess.check_output(['git', 'ls-files', '-z']).decode().split('\0')
failures = []
for name in filter(None, files):
    path = PurePosixPath(name)
    if name.startswith('.release/'):
        continue  # Signed APK staging lives only on the isolated publication branch.
    if (path.parts[0] in {'private', 'secrets', 'data', 'backups'} or
            path.suffix in {'.apk', '.aab', '.p12', '.pfx', '.jks', '.keystore', '.key'} or
            name.endswith(('.local.md', '.private.md')) or path.name == 'signing.json'):
        failures.append(name + ': private artifact')
        continue
    data = Path(name).read_bytes()
    patterns = [rb'-----BEGIN (?:RSA |EC |OPENSSH |ENCRYPTED )?PRIVATE KEY-----',
                rb'gh[pousr]_[A-Za-z0-9]{30,}', rb'github_pat_[A-Za-z0-9_]{50,}']
    if any(re.search(pattern, data) for pattern in patterns):
        failures.append(name + ': possible credential (value omitted)')
if failures:
    raise SystemExit('\n'.join(failures))
print('Tracked release boundaries checked; no private artifacts or recognized credential patterns.')
