#!/usr/bin/env python3
"""Isolated Docker end-to-end test. Requires the three pinned images, free localhost:13000."""
import base64
import json
import os
from pathlib import Path
import subprocess
import tempfile
import time
import urllib.parse
import urllib.request
import uuid
from cryptography.fernet import Fernet
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import ec

ROOT=Path(__file__).resolve().parents[1]
name='teslatempo-logs-test-'+uuid.uuid4().hex[:8]
network=name+'-relay';relay=name+'-relay'
open_http=urllib.request.build_opener(urllib.request.ProxyHandler({}))

def run(args,**kwargs):return subprocess.run(args,check=True,capture_output=True,text=True,**kwargs).stdout

def eventually(action,seconds=60):
    until=time.monotonic()+seconds
    while True:
        try:return action()
        except Exception:
            if time.monotonic()>until:raise
            time.sleep(1)


with tempfile.TemporaryDirectory(prefix='teslatempo-logs-test-') as tmp:
    home=Path(tmp)/'observability';data=Path(tmp)/'data';data.mkdir();(data/'diagnostics').mkdir()
    secret=Path(tmp)/'secrets';secret.mkdir()
    token='synthetic-'+uuid.uuid4().hex
    (secret/'relay_api_token').write_text(token)
    (secret/'token_encryption_key').write_bytes(Fernet.generate_key())
    (secret/'tesla_public_key.pem').write_bytes(ec.generate_private_key(ec.SECP256R1()).public_key().public_bytes(serialization.Encoding.PEM,serialization.PublicFormat.SubjectPublicKeyInfo))
    for f in secret.iterdir():f.chmod(0o600)
    run(['python3',str(ROOT/'scripts/init-observability.py'),'--directory',str(home)])
    env=dict(os.environ,OBSERVABILITY_HOME=str(home),RELAY_DIAGNOSTICS_DIR=str(data/'diagnostics'),
             OBS_UID=str(os.getuid()),OBS_GID=str(os.getgid()),RELAY_NETWORK=network)
    compose=['docker','compose','-p',name,'-f',str(ROOT/'deploy/observability/compose.yaml')]
    run(['docker','network','create',network])
    try:
        run(['docker','run','-d','--name',relay,'--network',network,'--network-alias','relay','--user',f'{os.getuid()}:{os.getgid()}',
             '-v',str(data)+':/data','-v',str(secret)+':/run/secrets:ro','teslatempo-relay:0.5.1'])
        run(compose+['up','-d','--no-build'],env=env)
        collector=run(compose+['ps','-q','collector'],env=env).strip()
        def read_loki():
            code="import urllib.request,json,urllib.parse;u='http://loki:3100/loki/api/v1/query_range?'+urllib.parse.urlencode({'query':'{app=\"teslatempo\"}','limit':'1000'});o=urllib.request.build_opener(urllib.request.ProxyHandler({}));print(json.dumps(json.load(o.open(u,timeout=8))))"
            return json.loads(run(['docker','exec',collector,'python','-c',code],timeout=15))
        def grafana():
            with open_http.open('http://127.0.0.1:13000/api/health',timeout=3) as r:assert json.load(r)['database']=='ok'
        eventually(grafana)
        identity=str(uuid.uuid4())
        code="""import urllib.request,json,uuid,time
op=urllib.request.build_opener(urllib.request.ProxyHandler({}))
token=open('/run/secrets/relay_api_token').read().strip()
h={'Authorization':'Bearer '+token,'X-Request-ID':%r}
r=op.open(urllib.request.Request('http://127.0.0.1:8080/v1/vehicles/demo/charge-state?secret=DO_NOT_LOG',headers=h));assert r.status==200
from relay.diagnostics import event
row=event('android','status','network_error','0.9.0');row['request_id']=%r
payload={'report_id':row['report_id'],'consent':True,'events':[row]}
r=op.open(urllib.request.Request('http://127.0.0.1:8080/v1/diagnostics/events',data=json.dumps(payload).encode(),headers={**h,'Content-Type':'application/json'}));assert r.status==200
""" % (identity,identity)
        eventually(lambda:run(['docker','exec',relay,'python','-c',code]))
        def correlated():
            result=read_loki();raw=json.dumps(result)
            assert token not in raw and 'DO_NOT_LOG' not in raw and 'battery_level' not in raw
            events=[json.loads(v[1]) for stream in result['data']['result'] for v in stream['values']]
            assert {'android','relay'} <= {e['source'] for e in events if e['request_id']==identity}
            return events
        eventually(correlated)
        print('Correlation Android/relay stored in Loki; sensitive test data absent.')
        # Grafana is private and requires credentials for diagnostics queries.
        try:open_http.open('http://127.0.0.1:13000/api/datasources',timeout=3);raise AssertionError('anonymous datasource access')
        except urllib.error.HTTPError as e:assert e.code==401
        password=(home/'secrets/grafana_admin_password').read_text()
        authorization='Basic '+base64.b64encode(('admin:'+password).encode()).decode()
        with open_http.open(urllib.request.Request('http://127.0.0.1:13000/api/dashboards/uid/teslatempo-diagnostics',headers={'Authorization':authorization})) as r:
            assert json.load(r)['dashboard']['uid']=='teslatempo-diagnostics'
        query=urllib.parse.urlencode({'query':'{app="teslatempo"}','limit':'1000'})
        with open_http.open(urllib.request.Request('http://127.0.0.1:13000/api/datasources/proxy/uid/teslatempo-loki/loki/api/v1/query_range?'+query,headers={'Authorization':authorization})) as r:
            assert identity in r.read().decode()
        loki=run(compose+['ps','-q','loki'],env=env).strip()
        assert not json.loads(run(['docker','inspect',loki]))[0]['HostConfig']['PortBindings']
        run(['docker','stop','-t','3',loki])
        outage_id=str(uuid.uuid4())
        began=time.monotonic()
        run(['docker','exec',relay,'python','-c',code.replace(identity,outage_id)],timeout=15)
        assert time.monotonic()-began<10
        run(['docker','start',loki])
        def recovered():
            assert outage_id in json.dumps(read_loki())
        eventually(recovered)
        print('Loki outage does not block relay requests; buffered events arrive after recovery.')
        run(['docker','stop',relay])
        def down():
            events=[json.loads(v[1]) for stream in read_loki()['data']['result'] for v in stream['values']]
            assert any(e['source']=='collector' and e['outcome']=='unavailable' for e in events)
        eventually(down,30)
        print('Collector detects stopped relay; Grafana authentication and dashboard verified; Loki has no published port.')
    except Exception:
        print(run(compose+['logs','--no-color','--tail','25'],env=env))
        for service in ('grafana','loki','collector'):
            cid=run(compose+['ps','-a','-q',service],env=env).strip()
            if cid:
                info=json.loads(run(['docker','inspect',cid]))[0]
                print(service,{k:info['State'].get(k) for k in ('Status','ExitCode','OOMKilled','Error')})
        raise
    finally:
        subprocess.run(compose+['down'],env=env,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
        subprocess.run(['docker','rm','-f',relay],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
        subprocess.run(['docker','network','rm',network],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
