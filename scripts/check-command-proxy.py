#!/usr/bin/env python3
"""Offline Docker smoke test using synthetic keys; no external network or real vehicle."""
import importlib.util
import os
import subprocess
import tempfile
import uuid
from pathlib import Path
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import ec

spec=importlib.util.spec_from_file_location('proxy_tls',Path(__file__).with_name('init-command-proxy.py'))
module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
name='teslatempo-proxy-check-'+uuid.uuid4().hex[:10]
with tempfile.TemporaryDirectory(prefix='teslatempo-proxy-') as tmp:
    path=Path(tmp);module.generate(path)
    key=ec.generate_private_key(ec.SECP256R1())
    private=path/'tesla_private_key.pem'
    private.write_bytes(key.private_bytes(serialization.Encoding.PEM,serialization.PrivateFormat.PKCS8,serialization.NoEncryption()));private.chmod(0o600)
    args=['docker','run','-d','--name',name,'--network','none','--read-only','--cap-drop','ALL','--security-opt','no-new-privileges:true',
          '--user',f'{os.getuid()}:{os.getgid()}','-v',tmp+':/run/secrets:ro',
          '-e','TESLA_KEY_FILE=/run/secrets/tesla_private_key.pem',
          'teslatempo-command-proxy:0.1.0','-host','localhost','-port','4443','-cert','/run/secrets/proxy_tls_cert.pem','-tls-key','/run/secrets/proxy_tls_key.pem']
    subprocess.run(args,check=True,stdout=subprocess.DEVNULL)
    try:
        check="""
import ssl,time,urllib.request,urllib.error
context=ssl.create_default_context(cafile='/run/secrets/proxy_tls_cert.pem')
opener=urllib.request.build_opener(urllib.request.ProxyHandler({}),urllib.request.HTTPSHandler(context=context))
for attempt in range(20):
 try:
  opener.open('https://localhost:4443/api/1/vehicles/00000000000000000/command/set_charge_limit',data=b'{}',timeout=2)
  raise AssertionError('Unauthenticated command unexpectedly accepted')
 except urllib.error.HTTPError as e:
  assert e.code in (400,401,403,404,405)
  print('TLS verified; unauthenticated request refused; external networking disabled.')
  break
 except urllib.error.URLError:
  if attempt==19:raise
  time.sleep(.25)
"""
        subprocess.run(['docker','run','--rm','--network','container:'+name,'-v',tmp+':/run/secrets:ro',
            '--user',f'{os.getuid()}:{os.getgid()}','--entrypoint','python','teslatempo-relay:0.3.0','-c',check],check=True)
    finally:subprocess.run(['docker','rm','-f',name],check=True,stdout=subprocess.DEVNULL)
