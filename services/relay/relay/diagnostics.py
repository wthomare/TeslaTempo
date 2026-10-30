"""Strict diagnostic vocabulary: never accept a message, URL, payload or exception text."""
import json
import os
import re
import threading
import time
import uuid
from collections import deque
from pathlib import Path

OPERATIONS = frozenset(('status','vehicles','charge_state','observations','assistant_status','assistant_models',
    'assistant_explain','assistant_disconnect','tesla_connect','tesla_register','tesla_disconnect',
    'oauth_launch','oauth_callback','diagnostics_upload','diagnostics_export','startup','health','app_crash','unknown'))
OUTCOMES = frozenset(('ok','http_error','network_error','invalid_response','crash','started','stopped','unavailable','recovered'))
SITES = frozenset(('unknown','app.py','tesla.py','chatgpt.py','store.py','observations.py','config.py','commands.py','diagnostics.py','RelayGateway.kt','AssistantGateway.kt','MainActivity.kt','ChargingOptimizer.kt','TempoGateway.kt','PersonalViewModel.kt','DiagnosticLog.kt'))
KINDS = frozenset(('none','unknown','ValueError','TypeError','OSError','RuntimeError','UpstreamError','ChatGPTError','ValidationError','IllegalStateException','IllegalArgumentException','NullPointerException','IOException'))
FIELDS = frozenset(('id','request_id','at','source','operation','outcome','code','duration_ms','version','report_id','site','line','error_kind'))
UUID = re.compile(r'^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$')
VERSION = re.compile(r'^\d{1,3}\.\d{1,3}\.\d{1,3}(?:-dev)?$')


def validate(row, now=None):
    now = time.time() if now is None else now
    if not isinstance(row, dict) or set(row) != FIELDS: raise ValueError('diagnostic_schema')
    for k in ('id','request_id','report_id'):
        if not isinstance(row[k],str) or not UUID.fullmatch(row[k]): raise ValueError('diagnostic_id')
    if row['source'] not in ('android','relay','collector') or row['operation'] not in OPERATIONS or row['outcome'] not in OUTCOMES:
        raise ValueError('diagnostic_enum')
    if row['site'] not in SITES or row['error_kind'] not in KINDS:raise ValueError('diagnostic_site')
    if type(row['line']) is not int or not 0<=row['line']<=10000:raise ValueError('diagnostic_line')
    if not isinstance(row['version'],str) or not VERSION.fullmatch(row['version']):raise ValueError('diagnostic_version')
    for key, low, high in (('at',int((now-7*86400)*1000),int((now+120)*1000)),('code',0,599),('duration_ms',0,120000)):
        if type(row[key]) is not int or not low <= row[key] <= high:raise ValueError('diagnostic_range')
    return dict(row)


def event(source, operation, outcome, version, request_id=None, code=0, duration_ms=0, site="unknown", line=0, error_kind="none"):
    identity=str(uuid.uuid4())
    return validate(dict(id=identity,request_id=request_id or identity,report_id=identity,
        at=int(time.time()*1000),source=source,operation=operation,outcome=outcome,version=version,
        site=site,line=line,error_kind=error_kind,code=code,duration_ms=min(120000,max(0,int(duration_ms)))))


def operation(path):
    fixed={'/v1/status':'status','/v1/vehicles':'vehicles','/v1/tesla/connect':'tesla_connect',
           '/v1/tesla/register':'tesla_register','/v1/tesla/connection':'tesla_disconnect',
           '/oauth/launch':'oauth_launch','/oauth/callback':'oauth_callback',
           '/v1/diagnostics/events':'diagnostics_upload','/v1/diagnostics/export':'diagnostics_export'}
    if path in fixed:return fixed[path]
    if re.fullmatch(r'/v1/vehicles/[^/]+/charge-state',path):return 'charge_state'
    if re.fullmatch(r'/v1/vehicles/[^/]+/charge-observations',path):return 'observations'
    return {'/v1/chatgpt/status':'assistant_status','/v1/chatgpt/models':'assistant_models',
            '/v1/chatgpt/explain':'assistant_explain','/v1/chatgpt/connection':'assistant_disconnect'}.get(path,'unknown')


class Diagnostics:
    """Bounded ring; requests only touch memory. One optional asynchronous file writer.

    Export is an atomic snapshot, max 500 events / about 200 KiB. IO failure never
    fails an operation. The independent collector sees no relay data/secret volume.
    """
    def __init__(self, directory=None):
        self.directory = Path(directory) if directory else None
        self.rows=deque(maxlen=500);self.lock=threading.Lock();self.stop=threading.Event();self.thread=None
        self.dropped=0

    def add(self,row):
        try:row=validate(row)
        except (ValueError,TypeError):return False
        with self.lock:
            if any(x['id']==row['id'] for x in self.rows):return True
            if len(self.rows)==500:self.dropped+=1
            self.rows.append(row)
        return True

    def snapshot(self):
        with self.lock:return [dict(x) for x in self.rows if x['at'] >= int((time.time()-7*86400)*1000)]

    def start(self):
        if not self.directory:return
        # Restore only filtered rows; corrupt/untrusted files never enter the ring.
        try:
            raw=(self.directory/'events.json').read_bytes()
            if len(raw)<=262144:
                data=json.loads(raw)
                if isinstance(data,list):
                    for row in data[-500:]:self.add(row)
        except (OSError,ValueError,TypeError):pass
        def writer():
            while not self.stop.wait(2):self.flush()
        self.thread=threading.Thread(target=writer,name='diagnostics-writer',daemon=True);self.thread.start()

    def flush(self):
        if not self.directory:return
        try:
            self.directory.mkdir(parents=True,exist_ok=True,mode=0o700)
            target=self.directory/'events.json';temp=self.directory/'events.tmp'
            fd=os.open(temp,os.O_WRONLY|os.O_CREAT|os.O_TRUNC,0o600)
            with os.fdopen(fd,'w') as f:json.dump(self.snapshot(),f,separators=(',',':'))
            os.replace(temp,target)
        except OSError:pass

    def close(self):
        self.stop.set()
        if self.thread:self.thread.join(timeout=3)
        self.flush()
