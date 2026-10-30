import json
import time
from concurrent.futures import ThreadPoolExecutor
from urllib.parse import parse_qs, urlencode, urlsplit

import httpx
import jwt
import pytest
from cryptography.fernet import Fernet, InvalidToken
from cryptography.hazmat.primitives.asymmetric import rsa
from fastapi.testclient import TestClient

from relay.chatgpt import ChatGPT, ChatGPTError, ChatGPTStore, ISSUER, SCOPES, completed_text
from relay.app import create_app
from test_relay import config, auth


@pytest.fixture
def store(tmp_path):
    return ChatGPTStore(tmp_path / 'chatgpt', Fernet.generate_key())


def record():
    return {'client_id': 'oaiapp_test', 'subject': 'test-subject', 'issuer': ISSUER,
            'access_token': 'private-access', 'refresh_token': 'private-refresh',
            'id_token': 'private-id-token', 'expires_at': time.time() + 3600, 'scopes': SCOPES.split()}


def connect(store, expired=False):
    r = record()
    if expired: r['expires_at'] = 1
    with store.locked():
        data = store.load(); data['accounts'][r['client_id']] = r; data['active'] = r['client_id']; store.save(data)


def test_private_encrypted_persistent_state(store):
    host = store.load()['host_id']; connect(store)
    assert store.load()['host_id'] == host
    assert b'private-access' not in store.path.read_bytes()
    assert store.path.stat().st_mode & 0o777 == 0o600
    with pytest.raises(InvalidToken): ChatGPTStore(store.directory, Fernet.generate_key())


def test_pkce_and_returning_registration(store):
    client = ChatGPT(store)
    url, pending = client.begin('http://127.0.0.1:12345/auth/callback')
    q = parse_qs(urlsplit(url).query)
    assert q['client_id'] == ['dynamic_agent_client']
    assert q['agent_name_hint'] == ['TeslaTempo']
    assert q['code_challenge_method'] == ['S256']
    assert pending['verifier'] not in url
    connect(store)
    url2, p2 = client.begin('http://127.0.0.1:3333/auth/callback', 'oaiapp_test')
    q2 = parse_qs(urlsplit(url2).query)
    assert q2['client_id'] == ['oaiapp_test'] and 'agent_name_hint' not in q2
    assert q2['ext_agent_host_id'] == q['ext_agent_host_id']
    assert pending['state'] != p2['state'] and pending['nonce'] != p2['nonce']


@pytest.mark.parametrize('kind', ['state', 'duplicate', 'denied', 'client', 'expired'])
def test_callback_errors_do_not_exchange_tokens(store, kind):
    def fail(request): pytest.fail('Invalid callback reached upstream')
    client = ChatGPT(store, httpx.MockTransport(fail)); connect(store)
    _, pending = client.begin('http://127.0.0.1:12345/auth/callback', 'oaiapp_test')
    params = {'state': pending['state'], 'code': 'private-code', 'client_id': 'oaiapp_test'}
    if kind == 'state': params['state'] = 'wrong'
    if kind == 'denied': params['error'] = 'access_denied'
    if kind == 'client': params['client_id'] = 'oaiapp_other'
    if kind == 'expired': pending['expires_at'] = 0
    query = urlencode(params) + ('&state=other' if kind == 'duplicate' else '')
    with pytest.raises(ChatGPTError): client.finish(query, pending)
    assert store.load()['accounts']['oaiapp_test']['access_token'] == 'private-access'


@pytest.mark.parametrize('bad', [None, 'nonce', 'audience', 'issuer', 'expired', 'signature', 'subject'])
def test_oidc_validation_before_replacing_session(store, bad):
    key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    jwk = json.loads(jwt.algorithms.RSAAlgorithm.to_jwk(key.public_key())); jwk['kid'] = 'test-key'
    connect(store)
    pending = None
    def handler(req):
        if req.url.path.endswith('openid-configuration'):
            return httpx.Response(200, json={'issuer': ISSUER, 'jwks_uri': ISSUER + '/.well-known/jwks.json'})
        if req.url.path.endswith('jwks.json'): return httpx.Response(200, json={'keys': [jwk]})
        fields = parse_qs(req.content.decode())
        assert fields['client_id'] == ['oaiapp_test']
        assert fields['code_verifier'] == [pending['verifier']]
        assert fields['redirect_uri'] == [pending['redirect_uri']]
        claims = {'iss': ISSUER, 'aud': 'oaiapp_test', 'sub': 'test-subject', 'nonce': pending['nonce'], 'iat': int(time.time()), 'exp': int(time.time()) + 3600}
        if bad == 'nonce': claims['nonce'] = 'wrong'
        if bad == 'audience': claims['aud'] = 'other'
        if bad == 'issuer': claims['iss'] = 'https://example.com'
        if bad == 'expired': claims['exp'] = 1
        if bad == 'subject': claims['sub'] = 'another-user'
        signing = rsa.generate_private_key(public_exponent=65537, key_size=2048) if bad == 'signature' else key
        identity = jwt.encode(claims, signing, algorithm='RS256', headers={'kid': 'test-key'})
        return httpx.Response(200, json={'access_token': 'new-access', 'refresh_token': 'new-refresh', 'id_token': identity,
            'token_type': 'Bearer', 'expires_in': 3600, 'scope': SCOPES})
    client = ChatGPT(store, httpx.MockTransport(handler))
    _, pending = client.begin('http://127.0.0.1:12345/auth/callback', 'oaiapp_test')
    query = urlencode({'state': pending['state'], 'code': 'private-code'})
    if bad:
        with pytest.raises(ChatGPTError): client.finish(query, pending)
        assert store.load()['accounts']['oaiapp_test']['access_token'] == 'private-access'
    else:
        assert client.finish(query, pending)['plan_enabled'] is True
        assert store.load()['accounts']['oaiapp_test']['access_token'] == 'new-access'
        with pytest.raises(ChatGPTError): client.finish(query, pending)


def test_concurrent_refresh_rotates_once_and_saves_atomically(store):
    connect(store, expired=True); refreshes = []
    def handler(req):
        if req.url.path.endswith('/token'):
            data = parse_qs(req.content.decode()); assert 'scope' not in data
            assert data['refresh_token'] == ['private-refresh']; refreshes.append(1)
            return httpx.Response(200, json={'token_type': 'Bearer', 'expires_in': 3600, 'access_token': 'next', 'refresh_token': 'rotated'})
        assert req.headers['Authorization'] == 'Bearer next'
        return httpx.Response(200, json={'models': [{'slug': 'available', 'display_name': 'Available', 'visibility': 'list'},
            {'slug': 'hidden', 'display_name': 'Hidden', 'visibility': 'hide'}]})
    client = ChatGPT(store, httpx.MockTransport(handler))
    with ThreadPoolExecutor(max_workers=4) as pool:
        results = list(pool.map(lambda _: client.models(), range(4)))
    assert refreshes == [1] and all(r == [{'id': 'available', 'name': 'Available'}] for r in results)
    assert store.load()['accounts']['oaiapp_test']['refresh_token'] == 'rotated'


def event(kind, **kwargs): return ('data: ' + json.dumps({'type': kind, **kwargs}) + '\n\n').encode()


def completed():
    return event('response.completed', response={'status': 'completed', 'output': [{'type': 'message', 'content': [{'type': 'output_text', 'text': 'Explication.'}]}]})


@pytest.mark.parametrize('suffix', [b'', event('response.failed'), event('response.incomplete'), event('error'), b'data: invalid\n\n'])
def test_partial_or_failed_stream_is_never_success(suffix):
    with pytest.raises(ChatGPTError): completed_text([event('response.output_text.delta', delta='partial'), suffix])


def test_completed_stream_and_bounds():
    raw = completed()
    assert completed_text([raw[:10], raw[10:25], raw[25:]]) == 'Explication.'
    with pytest.raises(ChatGPTError): completed_text([b'x' * 1_048_577])


def test_explanation_contract_no_tools_no_paid_key(store):
    connect(store)
    def handler(req):
        assert req.headers['Authorization'] == 'Bearer private-access'
        if req.url.path.endswith('/models'):
            return httpx.Response(200, json={'models': [{'slug': 'available', 'display_name': 'Available', 'visibility': 'list'}]})
        payload = json.loads(req.content)
        assert payload['store'] is False and payload['stream'] is True
        assert payload['model'] == 'available' and payload['input'][0]['content'] == 'Synthetic simulation'
        assert 'tools' not in payload and 'temperature' not in payload
        return httpx.Response(200, content=completed())
    client = ChatGPT(store, httpx.MockTransport(handler))
    assert client.explain('available', 'Synthetic simulation', 'oaiapp_test') == 'Explication.'
    with pytest.raises(ChatGPTError): client.explain('unavailable', 'Synthetic simulation', 'oaiapp_test')


def test_identity_only_cannot_infer(store):
    connect(store)
    with store.locked():
        data = store.load(); data['accounts']['oaiapp_test']['scopes'] = ['openid']; store.save(data)
    def fail(req): pytest.fail('Identity-only session attempted inference')
    client = ChatGPT(store, httpx.MockTransport(fail))
    assert not client.status()['plan_enabled']
    with pytest.raises(ChatGPTError): client.models()


def test_disconnect_clears_tokens_even_when_remote_is_unavailable(store):
    connect(store); host = store.load()['host_id']
    client = ChatGPT(store, httpx.MockTransport(lambda r: httpx.Response(503)))
    assert client.disconnect() == {'connected': False, 'remote_revocation_confirmed': False}
    data = store.load(); assert data['host_id'] == host
    assert data['accounts']['oaiapp_test']['subject'] == 'test-subject'
    assert not any(x in data['accounts']['oaiapp_test'] for x in ['access_token', 'refresh_token', 'id_token'])


def test_api_auth_consent_bounds_and_no_input_echo(config):
    def fail(req): pytest.fail('Rejected API request reached upstream')
    app = create_app(config, httpx.MockTransport(fail))
    with TestClient(app) as client:
        assert client.get('/v1/chatgpt/status').status_code == 401
        assert client.get('/v1/chatgpt/status', headers=auth(config)).json()['connected'] is False
        payload = {'model': 'available', 'summary': 'private-summary', 'consent': False, 'account': 'oaiapp_test'}
        assert client.post('/v1/chatgpt/explain', headers=auth(config), json=payload).status_code == 422
        payload['extra'] = 'private-summary'
        r = client.post('/v1/chatgpt/explain', headers=auth(config), json=payload)
        assert r.status_code == 422 and 'private-summary' not in r.text
        assert client.post('/v1/chatgpt/explain', headers=auth(config), content=b'x'*32769).status_code == 413
        assert client.get('/v1/chatgpt/models', headers=auth(config)).status_code == 409


def test_changed_account_refuses_generation_before_network(store):
    connect(store)
    def fail(req): pytest.fail('Changed account sent data upstream')
    client = ChatGPT(store, httpx.MockTransport(fail))
    with pytest.raises(ChatGPTError, match='chatgpt_account_changed'):
        client.explain('available', 'Synthetic simulation', 'oaiapp_other')


def test_revoked_refresh_clears_tokens_without_repeated_exchange(store):
    connect(store, expired=True); calls = []
    def handler(req):
        calls.append(1)
        return httpx.Response(400, json={'error': 'invalid_grant'})
    client = ChatGPT(store, httpx.MockTransport(handler))
    for _ in range(2):
        with pytest.raises(ChatGPTError): client.models()
    assert calls == [1] and not client.status()['connected']
    assert 'oaiapp_test' in client.status()['accounts']


def test_usage_limit_after_partial_stream_remains_quota_error():
    with pytest.raises(ChatGPTError) as error:
        completed_text([event('response.output_text.delta', delta='partial'),
            event('response.failed', response={'error': {'code': 'subscription_sharing_usage_limit_exceeded'}})])
    assert error.value.status == 429


def test_cli_transfer_preserves_destination_host_and_tesla_data(tmp_path):
    import subprocess
    import sys
    from pathlib import Path
    source = tmp_path / 'source'; dest = tmp_path / 'destination'; transfer = tmp_path / 'transfer.json'
    source.mkdir(); dest.mkdir()
    source_key = Fernet.generate_key(); dest_key = Fernet.generate_key()
    (source / 'local.key').write_bytes(source_key); (dest / 'local.key').write_bytes(dest_key)
    a = ChatGPTStore(source, source_key); b = ChatGPTStore(dest, dest_key)
    connect(a); host = b.load()['host_id']
    def run(*args):
        result = subprocess.run([sys.executable, '-m', 'relay.chatgpt_cli', *map(str,args)], capture_output=True, text=True)
        assert result.returncode == 0, result.stderr
        assert 'private-access' not in result.stdout and 'private-refresh' not in result.stdout
    run('export', '--directory', source, '--file', transfer)
    assert transfer.stat().st_mode & 0o777 == 0o600
    run('import', '--directory', dest, '--file', transfer)
    assert b.load()['host_id'] == host and b.load()['host_id'] != a.load()['host_id']
    assert b.load()['accounts']['oaiapp_test']['access_token'] == 'private-access'


def test_completed_siwc_response_may_omit_already_streamed_output():
    chunks = [event('response.output_item.added', item={'type': 'message'}),
              event('response.output_text.delta', output_index=1, content_index=0, delta='Bonjour '),
              event('response.output_text.delta', output_index=1, content_index=0, delta='monde.'),
              event('response.completed', response={'status': 'completed', 'output': []})]
    assert completed_text(chunks) == 'Bonjour monde.'
    with pytest.raises(ChatGPTError): completed_text(chunks[:-1])


def test_streamed_tool_or_excessive_text_is_rejected_even_before_completion():
    with pytest.raises(ChatGPTError):
        completed_text([event('response.output_item.added', item={'type': 'function_call'}), completed()])
    with pytest.raises(ChatGPTError):
        completed_text([event('response.output_text.delta', delta='x'*16001), completed()])
