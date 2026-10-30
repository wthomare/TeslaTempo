import dataclasses
import json
import time
import uuid

import httpx
import pytest
from fastapi.testclient import TestClient
from relay.app import create_app
from relay.diagnostics import Diagnostics,event,validate
from test_relay import config,auth,no_network


def test_correlation_and_no_secrets(config):
    with TestClient(create_app(config,httpx.MockTransport(no_network))) as c:
        identity=str(uuid.uuid4())
        r=c.get('/v1/vehicles/demo/charge-state?private=SECRET',headers={**auth(config),'X-Request-ID':identity})
        assert r.headers['X-Request-ID']==identity
        c.get('/oauth/callback?code=SECRET&state=SECRET')
        exported=c.get('/v1/diagnostics/export',headers=auth(config)).json()
        raw=json.dumps(exported)
        assert 'SECRET' not in raw and config.api_token not in raw and 'battery' not in raw and 'demo' not in raw
        assert any(x['request_id']==identity and x['operation']=='charge_state' for x in exported['events'])
        assert 'SECRET' not in c.get('/healthz',headers={'X-Request-ID':'SECRET'}).headers['X-Request-ID']


def test_upload_consent_schema_auth_and_export(config):
    with TestClient(create_app(config,httpx.MockTransport(no_network))) as c:
        row=event('android','status','network_error','0.9.0');payload={'report_id':row['report_id'],'consent':True,'events':[row]}
        assert c.post('/v1/diagnostics/events',json=payload).status_code==401
        assert c.post('/v1/diagnostics/events',headers=auth(config),json={**payload,'consent':False}).status_code==422
        assert c.post('/v1/diagnostics/events',headers=auth(config),json={**payload,'events':[{**row,'message':'SECRET'}]}).status_code==422
        assert c.post('/v1/diagnostics/events',headers=auth(config),json=payload).json()['accepted']==1
        assert c.post('/v1/diagnostics/events',headers=auth(config),json=payload).status_code==200
        rows=c.get('/v1/diagnostics/export',headers=auth(config)).json()['events']
        assert len([x for x in rows if x['id']==row['id']])==1
        assert c.post('/v1/diagnostics/events',headers=auth(config),content=b'x'*65537).status_code==413


@pytest.mark.parametrize('key,value',[('operation','https://SECRET'),('source','unknown'),('outcome','secret'),('at',0),('at',float('nan')),('code',True),('duration_ms',120001),('version','secret'),('request_id','secret'),('report_id','secret')])
def test_allowlist_rejects_freeform(key,value):
    with pytest.raises((ValueError,TypeError)):validate({**event('android','status','ok','0.9.0'),key:value})


def test_bounded_private_restart_and_io_failure(tmp_path):
    d=Diagnostics(tmp_path)
    for _ in range(510):d.add(event('relay','status','ok','0.5.0'))
    assert len(d.snapshot())==500 and d.dropped==10
    d.flush();assert (tmp_path/'events.json').stat().st_mode&0o777==0o600
    restored=Diagnostics(tmp_path);restored.start();assert len(restored.snapshot())==500;restored.close()
    # A failed diagnostic disk must not fail the request path.
    bad=Diagnostics(tmp_path/'events.json'/'not-a-directory');assert bad.add(event('relay','status','ok','0.5.0'));bad.flush()


def test_no_logging_unauthenticated_flood(config):
    with TestClient(create_app(config,httpx.MockTransport(no_network))) as c:
        for _ in range(100):c.get('/v1/status?token=SECRET')
        assert len(c.app.state.diagnostics.snapshot())==1 # startup only


def test_report_rate_limit(config):
    with TestClient(create_app(config,httpx.MockTransport(no_network))) as c:
        row=event('android','status','ok','0.9.0');payload={'report_id':row['report_id'],'consent':True,'events':[row]}
        for _ in range(5):assert c.post('/v1/diagnostics/events',headers=auth(config),json=payload).status_code==200
        assert c.post('/v1/diagnostics/events',headers=auth(config),json=payload).status_code==429


def test_crash_location_without_exception_text(config):
    from test_relay import real
    def crash(request):raise RuntimeError('SECRET exception with token and URL')
    app=create_app(real(config),httpx.MockTransport(crash))
    app.state.tesla.store.save({'access_token':'secret-token','refresh_token':'refresh','expires_at':time.time()+600})
    with TestClient(app,raise_server_exceptions=False) as c:
        assert c.get('/v1/vehicles',headers=auth(config)).status_code==500
        rows=app.state.diagnostics.snapshot()
        failures=[r for r in rows if r['outcome']=='crash']
        assert failures and failures[-1]['error_kind']=='RuntimeError' and failures[-1]['line']>0
        assert 'SECRET' not in json.dumps(rows) and 'secret-token' not in json.dumps(rows)
