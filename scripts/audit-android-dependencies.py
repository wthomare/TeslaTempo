#!/usr/bin/env python3
"""Check the resolved public Maven inventory against OSV; failure blocks CI."""
import json
from pathlib import Path
import urllib.request

path = Path('apps/android/app/build/reports/security/dependencies.json')
modules = json.loads(path.read_text())
if not modules:
    raise SystemExit('Empty Android dependency inventory')
query = {'queries': [{'package': {'ecosystem': 'Maven', 'name': row['name']}, 'version': row['version']} for row in modules]}
request = urllib.request.Request('https://api.osv.dev/v1/querybatch', data=json.dumps(query).encode(),
                                 headers={'Content-Type': 'application/json'})
with urllib.request.urlopen(request, timeout=30) as response:
    results = json.load(response)['results']
if len(results) != len(modules):
    raise SystemExit('Incomplete OSV response')
findings = []
for module, result in zip(modules, results):
    for vuln in result.get('vulns', []):
        findings.append(f"{module['name']}:{module['version']} {vuln['id']}")
if findings:
    raise SystemExit('\n'.join(findings))
print(f'{len(modules)} Maven modules checked; no known vulnerabilities reported by OSV.')
