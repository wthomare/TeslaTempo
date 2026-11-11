"""A malformed persisted collector state must not permanently block ingestion."""
import importlib.util
import json
from pathlib import Path
import sys

import pytest
from relay import diagnostics


@pytest.mark.parametrize('state', [
    {'seen': [[]], 'pending': [], 'health': True},
    {'seen': [], 'pending': [None], 'health': False},
    {'seen': [], 'pending': [], 'health': 1},
    None,
])
def test_corrupted_collector_state_recovers(tmp_path, monkeypatch, state):
    monkeypatch.setitem(sys.modules, 'schema', diagnostics)
    path = Path(__file__).resolve().parents[2] / 'diagnostics/collector.py'
    spec = importlib.util.spec_from_file_location('collector_under_test', path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    module.STATE = tmp_path/'state.json'
    module.STATE.write_text(json.dumps(state))
    assert module.restore_state() == {'seen': [], 'pending': [], 'health': None}
