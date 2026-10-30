import sqlite3
import time

import httpx
import pytest
from cryptography.fernet import Fernet, InvalidToken
from fastapi.testclient import TestClient
from relay.app import create_app
from relay.observations import Observations
from test_relay import config,auth,real


def sample(at, energy=12.0):
    return {'timestamp':int(at*1000),'charging_state':'Charging','battery_level':60,
            'charge_energy_added':energy,'charger_actual_current':10,'latitude':12.34,'vin':'PRIVATE-VIN'}


def test_snapshots_are_encrypted_deduplicated_and_not_summed(tmp_path):
    key=Fernet.generate_key(); store=Observations(tmp_path,key);at=time.time()
    assert store.record('private-id',sample(at),at)
    assert not store.record('private-id',sample(at),at)
    assert store.record('private-id',sample(at+1,12.5),at+1)
    records=Observations(tmp_path,key).list('private-id')
    assert len(records)==2 and records[0]['session_counter_kwh']==12.5
    assert records[0]['cost_euros'] is None
    raw=store.path.read_bytes()
    for value in [b'private-id',b'PRIVATE-VIN',b'Charging',b'session_counter_kwh',b'latitude']:assert value not in raw
    assert store.path.stat().st_mode & 0o777==0o600
    with pytest.raises(InvalidToken):Observations(tmp_path,Fernet.generate_key()).cipher.decrypt(sqlite3.connect(store.path).execute('SELECT value FROM samples').fetchone()[0])


@pytest.mark.parametrize('stamp',[None,True,float('nan'),-1,1e30])
def test_missing_stale_or_invalid_dates_are_not_fabricated(tmp_path,stamp):
    store=Observations(tmp_path,Fernet.generate_key());row=sample(time.time());row['timestamp']=stamp
    assert not store.record('id',row)
    assert store.list('id')==[]


def test_missing_energy_is_unknown_and_counter_reset_is_not_negative_consumption(tmp_path):
    store=Observations(tmp_path,Fernet.generate_key());at=time.time()
    for i,e in enumerate([15.0,0.0,None,float('nan'),-1,True]):assert store.record('id',sample(at+i,e),at+i)
    assert [r['session_counter_kwh'] for r in store.list('id')]==[None,None,None,None,0.0,15.0]
    assert all(r['cost_euros'] is None for r in store.list('id'))


def test_retention_and_clear_preserve_other_vehicle(tmp_path):
    store=Observations(tmp_path,Fernet.generate_key());at=time.time()
    for i in range(305):store.record('a',sample(at+i),at+i)
    store.record('b',sample(at),at)
    assert len(store.list('a'))==300
    store.clear('a');assert store.list('a')==[] and len(store.list('b'))==1


def test_mock_history_empty_not_fake_real_sessions(config):
    def fail(r):pytest.fail('Mock must not contact Tesla')
    with TestClient(create_app(config,httpx.MockTransport(fail))) as c:
        assert c.get('/v1/vehicles/demo/charge-observations').status_code==401
        r=c.get('/v1/vehicles/demo/charge-observations',headers=auth(config)).json()
        assert r['simulated'] and r['samples']==[] and r['total_energy_kwh'] is None
        assert c.delete('/v1/vehicles/demo/charge-observations',headers=auth(config)).json()['cleared']


def test_real_read_collects_without_additional_vehicle_data_request(config):
    cfg=real(config); calls=[];at=time.time()
    def handler(req):
        calls.append(req.url.path)
        if req.url.path.endswith('/vehicles'):return httpx.Response(200,json={'response':[{'id':123,'display_name':'Synthetic','state':'online'}]})
        return httpx.Response(200,json={'response':{'charge_state':sample(at)}})
    app=create_app(cfg,httpx.MockTransport(handler))
    app.state.tesla.store.save({'access_token':'synthetic','refresh_token':'synthetic','expires_at':at+3600})
    with TestClient(app) as c:
        for _ in range(2):assert c.get('/v1/vehicles/123/charge-state',headers=auth(cfg)).status_code==200
        result=c.get('/v1/vehicles/123/charge-observations',headers=auth(cfg)).json()
        assert len(result['samples'])==1 and result['total_cost_euros'] is None
        assert calls==['/api/1/vehicles','/api/1/vehicles/123/vehicle_data']
        assert c.get('/v1/vehicles/456/charge-observations',headers=auth(cfg)).status_code==404


def test_global_retention_is_bounded_across_vehicles(tmp_path):
    store=Observations(tmp_path,Fernet.generate_key());at=time.time()
    for v in ['a','b','c','d']:
        for i in range(260):store.record(v,sample(at+i),at+i)
    with sqlite3.connect(store.path) as db:assert db.execute('SELECT count(*) FROM samples').fetchone()[0]==1000


def test_same_timestamp_with_float_representation_is_deduplicated(tmp_path):
    store=Observations(tmp_path,Fernet.generate_key());at=time.time();row=sample(at)
    assert store.record('id',row,at)
    row['timestamp']=float(row['timestamp'])
    assert not store.record('id',row,at)


def test_clear_does_not_require_a_tesla_network_call(config):
    def fail(r):pytest.fail('Deleting local snapshots must not call Tesla')
    with TestClient(create_app(real(config),httpx.MockTransport(fail))) as c:
        assert c.delete('/v1/vehicles/123/charge-observations',headers=auth(config)).json()['cleared']
