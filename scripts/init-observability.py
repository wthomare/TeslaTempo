#!/usr/bin/env python3
"""Prepare private observability data and one-time Grafana password, never overwrite."""
import argparse
import os
from pathlib import Path
import secrets

p=argparse.ArgumentParser(description=__doc__)
p.add_argument('--directory',type=Path,required=True)
p.add_argument('--uid',type=int,default=os.getuid());p.add_argument('--gid',type=int,default=os.getgid())
a=p.parse_args();root=a.directory.resolve();root.mkdir(parents=True,exist_ok=True,mode=0o700)
for d in [root]+[root/x for x in ('loki','grafana','collector','secrets')]:
    d.mkdir(exist_ok=True,mode=0o700);d.chmod(0o700);os.chown(d,a.uid,a.gid)
password=root/'secrets/grafana_admin_password'
if not password.exists():
    fd=os.open(password,os.O_CREAT|os.O_EXCL|os.O_WRONLY,0o600)
    with os.fdopen(fd,'w') as f:f.write(secrets.token_urlsafe(36))
    os.chown(password,a.uid,a.gid)
print('Private directories ready. Grafana password file:',password)
