#!/usr/bin/env python3
"""Run the command chain against a synthetic car, with no network or credentials."""
import dataclasses
import json
from pathlib import Path
import sys
import tempfile
import time

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'services/relay'))
from cryptography.fernet import Fernet
from relay.commands import Program, Evidence, Executor, Journal, Refused, Uncertain


def main():
    now = int(time.time())
    program = Program('TEST0000000000001', ((now + 3600) // 60) * 60, 80, 10, 48.0, 2.0, 42)
    evidence = Evidence(program.vin, now, 50, True, True, True, True, True, 'empty', True, True, True)
    scenarios = {'verified': 'VERIFIED', 'sleeping': 'NOT_SENT', 'unplugged': 'NOT_SENT',
                 'conflict': 'NOT_SENT', 'timeout': 'UNKNOWN', 'partial': 'PARTIAL', 'readback_mismatch': 'UNKNOWN'}
    for scenario, expected in scenarios.items():
        class Simulator:
            sent = 0
            def read(self, vin):
                return dataclasses.replace(evidence, online=scenario != 'sleeping', plugged=scenario != 'unplugged',
                                           schedules_empty=scenario != 'conflict')
            def send(self, vin, command, payload):
                self.sent += 1
                if scenario == 'timeout': raise Uncertain()
                if scenario == 'partial' and self.sent == 2: raise Refused()
            def matches(self, value): return scenario != 'readback_mismatch'
        with tempfile.TemporaryDirectory(prefix='teslatempo-rehearsal-') as directory:
            journal = Journal(Path(directory), Fernet.generate_key())
            ticket, fingerprint = journal.prepare(program, evidence, now)
            result = Executor(journal, Simulator(), lambda: now).confirm(ticket, fingerprint, True)
            assert result['state'] == expected
            print(json.dumps({'scenario': scenario, 'simulated': True, **result}))


if __name__ == '__main__': main()
