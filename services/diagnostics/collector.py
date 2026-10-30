"""Minimal isolated collector: sanitized snapshot + relay health, no Docker socket."""
import json
import os
import time
import urllib.request
from pathlib import Path
from schema import validate, event

STATE=Path('/state/collector.json')
LOGS=Path('/logs/events.json')
OPENER=urllib.request.build_opener(urllib.request.ProxyHandler({}))


def load(path, maximum):
    with path.open('rb') as f:raw=f.read(maximum+1)
    if len(raw)>maximum:raise ValueError('size')
    return json.loads(raw)


def save(state):
    temp=STATE.with_suffix('.tmp')
    fd=os.open(temp,os.O_WRONLY|os.O_CREAT|os.O_TRUNC,0o600)
    with os.fdopen(fd,'w') as f:json.dump(state,f,separators=(',',':'))
    os.replace(temp,STATE)


def step(state):
    try:
        with OPENER.open(os.environ.get('RELAY_HEALTH_URL','http://relay:8080/healthz'),timeout=3) as r:
            health=r.status==200 and json.loads(r.read(2048)).get('status')=='ok'
    except Exception:health=False
    if health!=state['health']:
        state['pending'].append(event('collector','health','recovered' if health else 'unavailable','0.1.0'))
        state['pending']=state['pending'][-100:];state['health']=health
    try:rows=load(LOGS,262144)
    except Exception:rows=[]
    if not isinstance(rows,list):rows=[]
    seen=set(state['seen']);batch=[]
    for row in state['pending']+rows[-500:]:
        try:row=validate(row)
        except (ValueError,TypeError):continue
        if row['id'] not in seen:
            batch.append(row);seen.add(row['id'])
        if len(batch)>=100:break
    save(state)  # Persist health transitions even when Loki is unavailable.
    if not batch:return
    streams=[]
    now=time.time_ns()
    for source in ('relay','android','collector'):
        values=[[str(now+i),json.dumps(row,separators=(',',':'))] for i,row in enumerate(batch) if row['source']==source]
        if values:streams.append({'stream':{'app':'teslatempo','source':source},'values':values})
    payload=json.dumps({'streams':streams}).encode()
    req=urllib.request.Request(os.environ.get('LOKI_PUSH_URL','http://loki:3100/loki/api/v1/push'),
        data=payload,headers={'Content-Type':'application/json'},method='POST')
    with OPENER.open(req,timeout=5) as r:
        if r.status!=204:raise ValueError('push_failed')
    accepted={row['id'] for row in batch}
    state['seen']=(state['seen']+[row['id'] for row in batch])[-2000:]
    state['pending']=[row for row in state['pending'] if row['id'] not in accepted]
    save(state)


def main():
    try:
        state=load(STATE,524288)
        assert isinstance(state['seen'],list) and len(state['seen'])<=2000
        assert isinstance(state['pending'],list) and len(state['pending'])<=100
        assert state['health'] in (True,False,None)
    except Exception:state={'seen':[],'pending':[],'health':None}
    while True:
        try:step(state)
        except Exception:pass  # No raw exception strings, request bodies or credentials in stdout.
        time.sleep(5)


if __name__=='__main__':main()
