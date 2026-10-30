import dataclasses
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone

import httpx
import pytest
from cryptography.fernet import Fernet

from relay.commands import Program, Evidence, Executor, Journal, Refused, Uncertain, SignedTransport

NOW = int(datetime(2026, 1, 15, 19, tzinfo=timezone.utc).timestamp())
VIN = 'TEST0000000000001'


def program():
    return Program(VIN, NOW + 3600, 80, 10, 48.0, 2.0, 42)


def evidence():
    return Evidence(VIN, NOW, 50, True, True, True, True, True, 'empty-v1', True, True, True)


class Fake:
    def __init__(self, mode='ok'):
        self.sent = []; self.mode = mode

    def read(self, vin):
        e = evidence()
        if self.mode == 'sleeping': return dataclasses.replace(e, online=False)
        if self.mode == 'unplugged': return dataclasses.replace(e, plugged=False)
        if self.mode == 'existing': return dataclasses.replace(e, schedules_empty=False)
        if self.mode == 'changed': return dataclasses.replace(e, schedules_revision='changed')
        if self.mode == 'stale': return dataclasses.replace(e, at=NOW - 120)
        return e

    def send(self, vin, command, payload):
        if self.mode == 'refused': raise Refused()
        self.sent.append(command)
        if self.mode == 'timeout': raise Uncertain()
        if self.mode == 'partial' and len(self.sent) == 2: raise Refused()

    def matches(self, program):
        if self.mode == 'read_timeout': raise Uncertain()
        return self.mode != 'mismatch'


@pytest.mark.parametrize('mode,expected,count', [
    ('ok','VERIFIED',3), ('sleeping','NOT_SENT',0), ('unplugged','NOT_SENT',0),
    ('existing','NOT_SENT',0), ('changed','NOT_SENT',0), ('stale','NOT_SENT',0),
    ('refused','NOT_SENT',0), ('timeout','UNKNOWN',0), ('partial','PARTIAL',1),
    ('read_timeout','UNKNOWN',3), ('mismatch','UNKNOWN',3),
])
def test_execution_outcomes(tmp_path, mode, expected, count):
    j = Journal(tmp_path, Fernet.generate_key()); identity, fingerprint = j.prepare(program(), evidence(), NOW)
    fake = Fake(mode); executor = Executor(j, fake, lambda: NOW)
    result = executor.confirm(identity, fingerprint, True)
    assert result == {'state': expected, 'acknowledged': count, 'retry_allowed': False}
    sent = list(fake.sent)
    with pytest.raises(Refused): executor.confirm(identity, fingerprint, True)
    assert fake.sent == sent


def test_restart_encryption_and_crash_no_retry(tmp_path):
    key = Fernet.generate_key(); j = Journal(tmp_path, key); identity, fingerprint = j.prepare(program(), evidence(), NOW)
    assert VIN.encode() not in j.path.read_bytes()
    assert b'latitude' not in j.path.read_bytes()
    assert j.path.stat().st_mode & 0o777 == 0o600
    j.claim(identity, fingerprint, True, NOW)
    recovered = Journal(tmp_path, key)
    assert recovered.result(identity)['state'] == 'UNKNOWN'
    with pytest.raises(Refused): recovered.claim(identity, fingerprint, True, NOW)
    with pytest.raises(Refused): recovered.prepare(program(), evidence(), NOW)


@pytest.mark.parametrize('change', [dict(target=81), dict(amps=11), dict(target=True), dict(start=NOW+60),
    dict(start=NOW+19*3600), dict(latitude=float('nan')), dict(vin='../vehicles')])
def test_invalid_program(tmp_path, change):
    with pytest.raises(Refused): Journal(tmp_path, Fernet.generate_key()).prepare(dataclasses.replace(program(), **change), evidence(), NOW)


@pytest.mark.parametrize('change', [dict(parked=False),dict(idle=False),dict(at_home=None),dict(key_verified=False),
    dict(programming_verified=False),dict(at=NOW+1),dict(battery=float('nan')),dict(battery=80)])
def test_missing_evidence(tmp_path, change):
    with pytest.raises(Refused): Journal(tmp_path, Fernet.generate_key()).prepare(program(),dataclasses.replace(evidence(), **change),NOW)


@pytest.mark.parametrize('fingerprint,consent,offset', [('changed',True,0),('same',False,0),('same',True,90)])
def test_invalid_confirmation_consumed(tmp_path, fingerprint, consent, offset):
    j=Journal(tmp_path,Fernet.generate_key()); identity,fp=j.prepare(program(),evidence(),NOW)
    with pytest.raises(Refused): j.claim(identity,fp if fingerprint=='same' else fingerprint,consent,NOW+offset)
    assert j.result(identity)['state']=='NOT_SENT'
    with pytest.raises(Refused): j.claim(identity,fp,True,NOW)


def test_concurrent_confirmation_and_prepare(tmp_path):
    j=Journal(tmp_path,Fernet.generate_key());identity,fp=j.prepare(program(),evidence(),NOW)
    with pytest.raises(Refused):j.prepare(program(),evidence(),NOW)
    fake=Fake();executor=Executor(j,fake,lambda:NOW)
    def confirm(_):
        try:return executor.confirm(identity,fp,True)['state']
        except Refused:return 'REFUSED'
    with ThreadPoolExecutor(2) as pool: results=list(pool.map(confirm,range(2)))
    assert sorted(results)==['REFUSED','VERIFIED'] and len(fake.sent)==3


def test_program_one_time_local_time():
    rows=program().commands();schedule=rows[-1][1]
    assert [r[0] for r in rows]==['set_charging_amps','set_charge_limit','add_charge_schedule']
    assert schedule['start_time']==21*60 and schedule['days_of_week']=='Thursday'
    assert schedule['one_time'] is True and schedule['end_enabled'] is False


def test_clock_change_blocked(tmp_path):
    now=int(datetime(2026,3,28,22,tzinfo=timezone.utc).timestamp())
    with pytest.raises(Refused,match='clock_change'):
        dataclasses.replace(program(),start=now+6*3600).validate(now)


@pytest.mark.parametrize('status,body,outcome', [(200,{'response':{'result':True}},None),
    (200,{'response':{'result':False}},Refused),(200,{'response':{}},Uncertain),
    (302,{},Uncertain),(429,{},Refused),(503,{},Uncertain)])
def test_proxy_responses(tmp_path,status,body,outcome):
    # Use system root bundle only to construct TLS context; requests are intercepted.
    import ssl
    calls=[]
    def handler(request):
        calls.append(request)
        assert request.url.host=='localhost' and request.url.port==4443
        return httpx.Response(status,json=body,headers={'location':'https://other.example'})
    proxy=SignedTransport(ssl.get_default_verify_paths().cafile,lambda:'synthetic',httpx.MockTransport(handler))
    try:
        if outcome:
            with pytest.raises(outcome): proxy.send(VIN,*program().commands()[0])
        else:proxy.send(VIN,*program().commands()[0])
        assert len(calls)==1
        with pytest.raises(Refused):proxy.send(VIN,'charge_start',{})
        assert len(calls)==1
    finally:proxy.close()


@pytest.mark.parametrize('change', ['none','stale','wrong_vin','wrong_current','missing_schedules','extra_schedule','changed_time','boolean_id'])
def test_strict_readback(change):
    from relay.commands import VehicleAdapter, Snapshot
    p=program();e=evidence();current=10;rows=(p.commands()[-1][1],)
    if change=='stale':e=dataclasses.replace(e,at=NOW-1)
    if change=='wrong_vin':e=dataclasses.replace(e,vin='TEST0000000000002')
    if change=='wrong_current':current=9
    if change=='missing_schedules':rows=None
    if change=='extra_schedule':rows=rows+rows
    if change=='changed_time':rows=({**rows[0],'start_time':12},)
    if change=='boolean_id':rows=({**rows[0],'id':True},)
    class Signer:
        def send(self,*args):pass
    adapter=VehicleAdapter(lambda vin:Snapshot(e,current,80,rows),Signer(),lambda:NOW)
    assert not adapter.matches(p)
    adapter.send(p.vin,*p.commands()[0])
    assert adapter.matches(p)==(change=='none')


def test_transport_rejects_unsafe_payload_without_network():
    import ssl
    proxy=SignedTransport(ssl.get_default_verify_paths().cafile,lambda:'synthetic',httpx.MockTransport(lambda r:pytest.fail('network')))
    try:
        for command,payload in [('set_charge_limit',{'percent':100}),('set_charging_amps',{'charging_amps':True}),
            ('set_charging_amps',{'charging_amps':11}),('add_charge_schedule',{**program().commands()[-1][1],'one_time':False})]:
            with pytest.raises(Refused):proxy.send(VIN,command,payload)
    finally:proxy.close()
