"""Bounded encrypted snapshots, never inferred complete charging sessions."""
import hashlib
import hmac
import json
import math
import os
import sqlite3
import time
from datetime import datetime, timezone

from cryptography.fernet import Fernet


class Observations:
    def __init__(self, directory, key):
        self.path = directory / 'charge-observations.sqlite3'
        self.cipher = Fernet(key)
        self.identity_key = hashlib.sha256(b'teslatempo-observations-v1\0' + key).digest()
        fd=os.open(self.path,os.O_CREAT|os.O_WRONLY,0o600);os.close(fd)
        os.chmod(self.path,0o600)
        with sqlite3.connect(self.path) as db:
            db.execute('CREATE TABLE IF NOT EXISTS samples (id TEXT PRIMARY KEY, vehicle TEXT NOT NULL, value BLOB NOT NULL)')

    def vehicle_key(self, vehicle_id):
        return hmac.new(self.identity_key, vehicle_id.encode(), hashlib.sha256).hexdigest()

    def record(self, vehicle_id, row, received_at=None):
        if not isinstance(row,dict): return False
        received_at = time.time() if received_at is None else received_at
        stamp=row.get('timestamp')
        if isinstance(stamp,bool) or not isinstance(stamp,(int,float)) or not math.isfinite(stamp):return False
        at=stamp/1000
        # A stale/cache replay is not a fresh measurement. Unknown time is not "now".
        if at < received_at-300 or at > received_at+60:return False
        def number(name, maximum):
            value=row.get(name)
            if isinstance(value,bool) or not isinstance(value,(int,float)) or not math.isfinite(value) or not 0 <= value <= maximum:return None
            return value
        state=row.get('charging_state')
        if state not in ['Charging','Starting','Stopped','Complete','Disconnected','NoPower']:state='Unknown'
        sample={'sampled_at':datetime.fromtimestamp(at,timezone.utc).isoformat(), 'state':state,
                'battery_percent':number('battery_level',100), 'session_counter_kwh':number('charge_energy_added',500),
                'current_amps':number('charger_actual_current',1000), 'cost_euros':None}
        vehicle=self.vehicle_key(vehicle_id)
        identity=hmac.new(self.identity_key,(vehicle+'\0'+str(int(stamp))).encode(),hashlib.sha256).hexdigest()
        sealed=self.cipher.encrypt(json.dumps(sample,allow_nan=False).encode())
        with sqlite3.connect(self.path) as db:
            changed=db.execute('INSERT OR IGNORE INTO samples VALUES (?,?,?)',(identity,vehicle,sealed)).rowcount
            db.execute('DELETE FROM samples WHERE vehicle=? AND rowid NOT IN (SELECT rowid FROM samples WHERE vehicle=? ORDER BY rowid DESC LIMIT 300)',(vehicle,vehicle))
            db.execute('DELETE FROM samples WHERE rowid NOT IN (SELECT rowid FROM samples ORDER BY rowid DESC LIMIT 1000)')
        return bool(changed)

    def list(self, vehicle_id):
        with sqlite3.connect(self.path) as db:
            rows=db.execute('SELECT value FROM samples WHERE vehicle=? ORDER BY rowid DESC LIMIT 300',(self.vehicle_key(vehicle_id),)).fetchall()
        records=[json.loads(self.cipher.decrypt(r[0])) for r in rows]
        return sorted(records,key=lambda x:x['sampled_at'],reverse=True)

    def clear(self, vehicle_id):
        with sqlite3.connect(self.path) as db:db.execute('DELETE FROM samples WHERE vehicle=?',(self.vehicle_key(vehicle_id),))
