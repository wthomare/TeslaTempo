"""Command orchestration, not wired to a production write route.

Evidence must come from a trusted fresh vehicle reader, never an Android payload.
The HTTP application only uses the synthetic rehearsal adapter below.
"""
import hashlib
import json
import math
import os
import re
import secrets
import sqlite3
import ssl
import time
from dataclasses import asdict, dataclass
from datetime import datetime
from zoneinfo import ZoneInfo

import httpx
from cryptography.fernet import Fernet


class Refused(Exception):
    pass


class Uncertain(Exception):
    pass


@dataclass(frozen=True)
class Program:
    vin: str
    start: int
    target: int
    amps: int
    latitude: float
    longitude: float
    schedule_id: int

    def validate(self, now):
        if not re.fullmatch(r'[A-HJ-NPR-Z0-9]{17}', self.vin): raise Refused('invalid_vehicle')
        for value in (self.start, self.target, self.amps, self.schedule_id):
            if type(value) is not int: raise Refused('invalid_program')
        if not (now + 120 <= self.start <= now + 18 * 3600) or self.start % 60: raise Refused('invalid_start')
        if not 1 <= self.target <= 80 or not 1 <= self.amps <= 10 or not 1 <= self.schedule_id < 2**32:
            raise Refused('invalid_limits')
        for value, limit in ((self.latitude, 90), (self.longitude, 180)):
            if type(value) not in (int, float) or not math.isfinite(value) or abs(value) > limit:
                raise Refused('invalid_location')
        zone = ZoneInfo('Europe/Paris')
        if datetime.fromtimestamp(now, zone).utcoffset() != datetime.fromtimestamp(self.start, zone).utcoffset():
            raise Refused('clock_change')

    def commands(self):
        local = datetime.fromtimestamp(self.start, ZoneInfo('Europe/Paris'))
        day = ('Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday', 'Saturday', 'Sunday')[local.weekday()]
        return [
            ('set_charging_amps', {'charging_amps': self.amps}),
            ('set_charge_limit', {'percent': self.target}),
            ('add_charge_schedule', {'id': self.schedule_id, 'lat': self.latitude, 'lon': self.longitude,
                'start_enabled': True, 'start_time': local.hour * 60 + local.minute, 'end_enabled': False,
                'end_time': 0, 'days_of_week': day, 'one_time': True, 'enabled': True}),
        ]


@dataclass(frozen=True)
class Evidence:
    vin: str
    at: float
    battery: float
    online: bool
    plugged: bool
    at_home: bool
    parked: bool
    idle: bool
    schedules_revision: str
    schedules_empty: bool
    key_verified: bool
    programming_verified: bool

    def validate(self, program, now):
        if self.vin != program.vin: raise Refused('vehicle_changed')
        if type(self.at) not in (int, float) or not math.isfinite(self.at) or not 0 <= now - self.at < 120:
            raise Refused('stale_evidence')
        for key in ('online', 'plugged', 'at_home', 'parked', 'idle', 'schedules_empty', 'key_verified', 'programming_verified'):
            if getattr(self, key) is not True: raise Refused(key)
        if not isinstance(self.schedules_revision, str) or not self.schedules_revision: raise Refused('unknown_schedules')
        if type(self.battery) not in (int, float) or not math.isfinite(self.battery) or not 0 <= self.battery < program.target:
            raise Refused('target_or_battery')


class Journal:
    """Encrypted, single-use confirmations; claim is committed before any write.

    A process interruption leaves APPLYING, exposed as UNKNOWN, never retried.
    SQLite BEGIN IMMEDIATE prevents two workers from consuming the same ticket.
    """
    def __init__(self, directory, key):
        self.path = directory / 'command-journal.sqlite3'
        self.cipher = Fernet(key)
        fd = os.open(self.path, os.O_CREAT | os.O_WRONLY, 0o600); os.close(fd)
        os.chmod(self.path, 0o600)
        with sqlite3.connect(self.path) as db:
            db.execute('CREATE TABLE IF NOT EXISTS attempts (id TEXT PRIMARY KEY, value BLOB NOT NULL)')

    def seal(self, row):
        return self.cipher.encrypt(json.dumps(row, allow_nan=False, sort_keys=True).encode())

    def prepare(self, program, evidence, now):
        program.validate(now); evidence.validate(program, now)
        identity = secrets.token_urlsafe(32)
        fingerprint = hashlib.sha256(json.dumps(asdict(program), sort_keys=True).encode()).hexdigest()
        row = {'program': asdict(program), 'evidence': asdict(evidence), 'fingerprint': fingerprint,
               'expires': min(now + 90, program.start - 120), 'state': 'PREPARED', 'acknowledged': 0}
        with sqlite3.connect(self.path) as db:
            db.execute('BEGIN IMMEDIATE')
            for (raw,) in db.execute('SELECT value FROM attempts'):
                previous = json.loads(self.cipher.decrypt(raw))
                if previous['program']['vin'] == program.vin and (previous['state'] in ('APPLYING', 'UNKNOWN', 'PARTIAL') or (previous['state'] == 'PREPARED' and previous['expires'] > now)):
                    raise Refused('vehicle_attempt_pending')
            # Bounded journal: fail rather than discard unresolved attempts.
            if db.execute('SELECT count(*) FROM attempts').fetchone()[0] >= 1000: raise Refused('journal_full')
            db.execute('INSERT INTO attempts VALUES (?,?)', (identity, self.seal(row)))
        return identity, fingerprint

    def read(self, identity):
        with sqlite3.connect(self.path) as db:
            row = db.execute('SELECT value FROM attempts WHERE id=?', (identity,)).fetchone()
        if row is None: raise Refused('unknown_confirmation')
        return json.loads(self.cipher.decrypt(row[0]))

    def claim(self, identity, fingerprint, consent, now):
        with sqlite3.connect(self.path) as db:
            db.execute('BEGIN IMMEDIATE')
            raw = db.execute('SELECT value FROM attempts WHERE id=?', (identity,)).fetchone()
            if raw is None: raise Refused('unknown_confirmation')
            row = json.loads(self.cipher.decrypt(raw[0]))
            if row['state'] != 'PREPARED': raise Refused('confirmation_consumed')
            valid = consent is True and secrets.compare_digest(row['fingerprint'], fingerprint) and now < row['expires']
            row['state'] = 'APPLYING' if valid else 'NOT_SENT'
            db.execute('UPDATE attempts SET value=? WHERE id=?', (self.seal(row), identity))
        if not valid: raise Refused('confirmation_invalid_or_expired')
        return row

    def finish(self, identity, row, state, acknowledged):
        row = {**row, 'state': state, 'acknowledged': acknowledged}
        with sqlite3.connect(self.path) as db:
            db.execute('UPDATE attempts SET value=? WHERE id=?', (self.seal(row), identity))
        return self.result(identity)

    def result(self, identity):
        row = self.read(identity)
        return {'state': 'UNKNOWN' if row['state'] == 'APPLYING' else row['state'],
                'acknowledged': row['acknowledged'], 'retry_allowed': False}


class Executor:
    def __init__(self, journal, adapter, clock=time.time):
        self.journal, self.adapter, self.clock = journal, adapter, clock

    def confirm(self, identity, fingerprint, consent):
        row = self.journal.claim(identity, fingerprint, consent, self.clock())
        count = 0
        try:
            program = Program(**row['program'])
            before = self.adapter.read(program.vin)  # uncached, trusted, no wake
            program.validate(self.clock()); before.validate(program, self.clock())
            old = Evidence(**row['evidence'])
            if before.schedules_revision != old.schedules_revision or abs(before.battery - old.battery) > 1:
                raise Refused('evidence_changed')
            for command, payload in program.commands():
                self.adapter.send(program.vin, command, payload)
                count += 1
                # Persist each ACK, but ACK alone never means applied/verified.
                self.journal.finish(identity, row, 'APPLYING', count)
            matched = self.adapter.matches(program)  # new uncached read, strict exact schedule/settings comparison
            return self.journal.finish(identity, row, 'VERIFIED' if matched is True else 'UNKNOWN', count)
        except Refused:
            return self.journal.finish(identity, row, 'NOT_SENT' if count == 0 else 'PARTIAL', count)
        except Exception:
            # Includes timeouts, malformed responses, storage failure after send and lost readback.
            return self.journal.finish(identity, row, 'UNKNOWN', count)


class SignedTransport:
    """Allowlisted local TLS proxy client. No retries, redirects, waking or tools.

    Not constructed by the production FastAPI app. Vehicle adapter validation and
    installation-specific key pairing are prerequisites for future activation.
    """
    def __init__(self, certificate, token, transport=None):
        context = ssl.create_default_context(cafile=str(certificate))
        self.token = token
        self.http = httpx.Client(base_url='https://localhost:4443', verify=context, transport=transport,
                                 timeout=httpx.Timeout(15, connect=5), follow_redirects=False, trust_env=False)

    def send(self, vin, command, payload):
        if not re.fullmatch(r'[A-HJ-NPR-Z0-9]{17}', vin): raise Refused('invalid_vehicle')
        allowed = {'set_charging_amps': {'charging_amps'}, 'set_charge_limit': {'percent'},
                   'add_charge_schedule': {'id','lat','lon','start_enabled','start_time','end_enabled','end_time','days_of_week','one_time','enabled'}}
        if command not in allowed or set(payload) != allowed[command]: raise Refused('command_not_allowed')
        def integer(key, low, high):
            return type(payload.get(key)) is int and low <= payload[key] <= high
        valid = True
        if command == 'set_charging_amps': valid = integer('charging_amps', 1, 10)
        elif command == 'set_charge_limit': valid = integer('percent', 1, 80)
        else:
            valid = (integer('id', 1, 2**32-1) and integer('start_time', 0, 1439) and
                     type(payload['end_time']) is int and payload['end_time'] == 0 and
                     payload['start_enabled'] is True and payload['end_enabled'] is False and
                     payload['one_time'] is True and payload['enabled'] is True and
                     payload['days_of_week'] in ('Monday','Tuesday','Wednesday','Thursday','Friday','Saturday','Sunday'))
            for key, limit in (('lat', 90), ('lon', 180)):
                value = payload[key]
                valid = valid and type(value) in (int, float) and math.isfinite(value) and abs(value) <= limit
        if not valid: raise Refused('invalid_command_payload')
        # Only exact payloads generated from a validated Program are admitted by the executor.
        try:
            with self.http.stream('POST', f'/api/1/vehicles/{vin}/command/{command}',
                    headers={'Authorization': 'Bearer ' + self.token()}, json=payload) as response:
                if response.status_code in (401, 403, 429): raise Refused('command_refused')
                if not response.is_success: raise Uncertain('command_unknown')
                raw = bytearray()
                for chunk in response.iter_bytes():
                    raw.extend(chunk)
                    if len(raw) > 65536: raise Uncertain('response_too_large')
                data = json.loads(raw)
                value = data.get('response', {}).get('result')
                if value is False: raise Refused('vehicle_refused')
                if value is not True: raise Uncertain('command_unknown')
        except (httpx.HTTPError, ValueError, AttributeError, TypeError):
            raise Uncertain('command_unknown') from None

    def close(self):
        self.http.close()


@dataclass(frozen=True)
class Snapshot:
    """Normalized trusted reader contract. Missing Tesla fields must stay unknown.

    A future Fleet reader must normalize its observed schema to this contract;
    no guessed/default values are allowed. It is not an HTTP request model.
    """
    evidence: Evidence
    current: int | None
    target: int | None
    schedules: tuple[dict, ...] | None


class VehicleAdapter:
    def __init__(self, reader, signer, clock=time.time):
        self.reader, self.signer, self.clock = reader, signer, clock
        self.last_writes = {}

    def read(self, vin):
        return self.reader(vin).evidence

    def send(self, vin, command, payload):
        self.last_writes[vin] = self.clock()
        self.signer.send(vin, command, payload)

    def matches(self, program):
        snapshot = self.reader(program.vin)  # reader must bypass caches
        e = snapshot.evidence
        last_write = self.last_writes.get(program.vin)
        if last_write is None or type(e.at) not in (int, float) or not math.isfinite(e.at): return False
        if not last_write <= e.at <= self.clock() or self.clock() - e.at >= 120: return False
        if e.vin != program.vin or not all(getattr(e, key) is True for key in ('online','plugged','at_home','parked')): return False
        if type(snapshot.current) is not int or snapshot.current != program.amps: return False
        if type(snapshot.target) is not int or snapshot.target != program.target: return False
        if snapshot.schedules is None or len(snapshot.schedules) != 1: return False
        expected = program.commands()[-1][1]
        actual = snapshot.schedules[0]
        if set(actual) != set(expected): return False
        # Do not let bool == int disguise a malformed readback.
        for key, value in expected.items():
            got = actual[key]
            if key in ('lat','lon'):
                if type(got) not in (int,float) or not math.isfinite(got) or abs(got-value)>0.00001: return False
            elif type(got) is not type(value) or got != value: return False
        return True
