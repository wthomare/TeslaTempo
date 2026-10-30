#!/usr/bin/env python3
"""Export filtered relay diagnostics to a private file; never print credentials."""
import argparse
import json
import os
import sys
from pathlib import Path
import urllib.request
from urllib.parse import urlsplit

sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'services/relay'))
from relay.diagnostics import validate

p=argparse.ArgumentParser(description=__doc__)
p.add_argument('--url',required=True);p.add_argument('--token-file',type=Path,required=True)
p.add_argument('--output',type=Path,required=True)
a=p.parse_args();url=a.url.rstrip('/');u=urlsplit(url)
if u.scheme!='https' or not u.hostname or u.username or u.password or u.path or u.query or u.fragment:
    p.error('HTTPS origin required, no credentials/path/query')
class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self,*args,**kwargs):return None
opener=urllib.request.build_opener(NoRedirect)
token=a.token_file.read_text().strip()
try:
    req=urllib.request.Request(url+'/v1/diagnostics/export',headers={'Authorization':'Bearer '+token})
    with opener.open(req,timeout=15) as r:raw=r.read(262145)
    if len(raw)>262144:raise ValueError()
    data=json.loads(raw)
    if data.get('schema')!=1 or len(data['events'])>500:raise ValueError()
    data['events']=[validate(row) for row in data['events']]
    fd=os.open(a.output,os.O_WRONLY|os.O_CREAT|os.O_EXCL,0o600)
    with os.fdopen(fd,'w') as f:json.dump(data,f,indent=2)
except Exception:raise SystemExit('Export failed: verify URL, token file, output path and relay availability.') from None
print('Private export saved:',a.output)
