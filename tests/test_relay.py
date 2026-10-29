import dataclasses
import time
from concurrent.futures import ThreadPoolExecutor
from urllib.parse import parse_qs, urlsplit

import httpx
import pytest
from cryptography.fernet import Fernet, InvalidToken
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import ec
from fastapi.testclient import TestClient

from relay.app import create_app
from relay.config import Config
from relay.store import Store
from relay.tesla import Tesla, UpstreamError


@pytest.fixture
def config(tmp_path):
    public = ec.generate_private_key(ec.SECP256R1()).public_key().public_bytes(
        serialization.Encoding.PEM, serialization.PublicFormat.SubjectPublicKeyInfo)
    return Config("mock", "http://localhost:8080", "test-" + "a" * 48, Fernet.generate_key(), public, tmp_path)


def auth(config):
    return {"Authorization": "Bearer " + config.api_token}


def real(config):
    return dataclasses.replace(config, mode="tesla", public_url="https://relay.example.com",
                               client_id="test-client", client_secret="test-client-secret")


def no_network(request):
    pytest.fail("A mock or rejected request must never contact Tesla")


def test_mock_routes_and_no_commands(config):
    with TestClient(create_app(config, httpx.MockTransport(no_network))) as client:
        assert client.get("/healthz").status_code == 200
        key = client.get("/.well-known/appspecific/com.tesla.3p.public-key.pem")
        assert key.content == config.public_key
        assert client.get("/v1/vehicles").status_code == 401
        assert client.get("/v1/vehicles", headers={"Authorization": "Bearer wrong"}).status_code == 401
        response = client.get("/v1/vehicles", headers=auth(config))
        assert response.json()["simulated"] is True
        assert response.headers["Cache-Control"] == "no-store"
        assert client.get("/v1/vehicles/demo/charge-state", headers=auth(config)).json()["charge_state"]["battery_level"] == 55
        assert client.get("/v1/vehicles/other/charge-state", headers=auth(config)).status_code == 404
        assert client.post("/v1/tesla/connect", headers=auth(config)).status_code == 409
        assert client.post("/api/1/vehicles/demo/command/charge_start", headers=auth(config)).status_code == 404
        assert client.get("/docs").status_code == 404


@pytest.mark.parametrize("changes", [
    {"api_token": "short"}, {"region": "arbitrary-host"}, {"mode": "production"},
    {"public_url": "http://public.example"}, {"public_url": "https://user:password@example.com"},
    {"public_url": "https://example.com/path"}, {"mode": "tesla"},
    {"public_key": b"not a key"}, {"encryption_key": b"short"},
])
def test_invalid_configuration_fails_closed(config, changes):
    with pytest.raises(ValueError):
        dataclasses.replace(config, **changes).validate()


def test_encrypted_store_survives_restart_and_wrong_key_fails(config):
    store = Store(config.data_dir, config.encryption_key)
    tokens = {"access_token": "secret-access", "refresh_token": "secret-refresh", "expires_at": 9999999999}
    store.save(tokens)
    assert b"secret-access" not in store.path.read_bytes()
    assert b"secret-refresh" not in store.path.read_bytes()
    assert Store(config.data_dir, config.encryption_key).load() == tokens
    with pytest.raises(InvalidToken):
        Store(config.data_dir, Fernet.generate_key())
    assert store.path.stat().st_mode & 0o777 == 0o600


def start_flow(client, config):
    result = client.post("/v1/tesla/connect", headers=auth(config))
    assert result.status_code == 200
    launch_url = result.json()["launch_url"]
    launch = client.get(launch_url, follow_redirects=False)
    assert launch.status_code == 302
    assert "HttpOnly" in launch.headers["set-cookie"]
    assert "Secure" in launch.headers["set-cookie"]
    params = parse_qs(urlsplit(launch.headers["location"]).query)
    assert params["scope"] == ["openid offline_access vehicle_device_data"]
    assert client.get(launch_url, follow_redirects=False).status_code == 400
    return params["state"][0]


def test_oauth_browser_binding_one_use_and_no_token_leak(config):
    config = real(config)
    calls = []

    def upstream(request):
        calls.append(request)
        form = parse_qs(request.content.decode())
        assert form["client_secret"] == [config.client_secret]
        assert form["redirect_uri"] == [config.callback]
        return httpx.Response(200, json={"access_token": "secret-access", "refresh_token": "secret-refresh", "expires_in": 3600})

    app = create_app(config, httpx.MockTransport(upstream))
    with TestClient(app, base_url=config.public_url) as client:
        state = start_flow(client, config)
        callback = "/oauth/callback?" + "state=" + state + "&code=test-code"
        # Possession of a state alone isn't sufficient: callback must use the same browser.
        stranger = TestClient(app, base_url=config.public_url)
        assert stranger.get(callback).status_code == 400
        stranger.close()
        assert not calls
        response = client.get(callback)
        assert response.status_code == 200
        assert "secret-access" not in response.text
        assert client.get(callback).status_code == 400
        assert len(calls) == 1
        assert client.get("/v1/status", headers=auth(config)).json()["tesla_connected"]
        new_state = start_flow(client, config)
        assert client.delete("/v1/tesla/connection", headers=auth(config)).status_code == 200
        assert client.get("/oauth/callback", params={"state": new_state, "code": "late-code"}).status_code == 400
        assert not app.state.tesla.store.load()


def test_oauth_expiry_and_denial(config, monkeypatch):
    config = real(config)
    clock = [1000.0]
    monkeypatch.setattr("relay.app.time.monotonic", lambda: clock[0])
    with TestClient(create_app(config, httpx.MockTransport(no_network)), base_url=config.public_url) as client:
        ticket = client.post("/v1/tesla/connect", headers=auth(config)).json()["launch_url"]
        clock[0] += 121
        assert client.get(ticket).status_code == 400
        state = start_flow(client, config)
        response = client.get("/oauth/callback", params={"state": state, "error": "access_denied"})
        assert response.status_code == 400
        assert "annulée" in response.text
        state = start_flow(client, config)
        clock[0] += 601
        assert client.get("/oauth/callback", params={"state": state, "code": "expired"}).status_code == 400


def connected_tesla(config, handler):
    config = real(config)
    store = Store(config.data_dir, config.encryption_key)
    store.save({"access_token": "old-access", "refresh_token": "old-refresh", "expires_at": 0})
    return Tesla(config, store, httpx.MockTransport(handler))


def test_concurrent_refresh_is_serialized_and_cached(config):
    calls = []

    def upstream(request):
        calls.append(request)
        if request.method == "POST":
            assert b"old-refresh" in request.content
            return httpx.Response(200, json={"access_token": "new-access", "refresh_token": "rotated-refresh", "expires_in": 3600})
        assert request.headers["Authorization"] == "Bearer new-access"
        return httpx.Response(200, json={"response": []})

    tesla = connected_tesla(config, upstream)
    with ThreadPoolExecutor(max_workers=8) as pool:
        results = list(pool.map(lambda _: tesla.get("/api/1/vehicles"), range(8)))
    assert all(result == {"response": []} for result in results)
    assert len(calls) == 2
    assert tesla.store.load()["refresh_token"] == "rotated-refresh"
    tesla.http.close()


@pytest.mark.parametrize("status,expected", [(401, 401), (429, 429), (500, 502), (408, 503), (302, 502)])
def test_upstream_errors_are_sanitized_without_retry(config, status, expected):
    calls = []

    def upstream(request):
        calls.append(request)
        return httpx.Response(status, json={"secret": "must-not-leak"}, headers={"location": "https://evil.example"})

    tesla = connected_tesla(config, upstream)
    with pytest.raises(UpstreamError) as result:
        tesla.get("/api/1/vehicles")
    assert result.value.status == expected
    assert "must-not-leak" not in result.value.code
    assert len(calls) == 1
    if status == 401:
        assert tesla.store.load() is None
    tesla.http.close()


def test_real_read_path_checks_owner_and_never_wakes(config):
    config = real(config)
    calls = []

    def upstream(request):
        calls.append(request)
        assert request.method == "GET"
        if request.url.path == "/api/1/vehicles":
            return httpx.Response(200, json={"response": [{"id": 123, "display_name": "Test"}]})
        assert request.url.path == "/api/1/vehicles/123/vehicle_data"
        return httpx.Response(200, json={"response": {"charge_state": {"battery_level": 60}}})

    app = create_app(config, httpx.MockTransport(upstream))
    app.state.tesla.store.save({"access_token": "access", "refresh_token": "refresh", "expires_at": time.time() + 3600})
    with TestClient(app, base_url=config.public_url) as client:
        assert client.get("/v1/vehicles/999/charge-state", headers=auth(config)).status_code == 404
        assert client.get("/v1/vehicles/123/charge-state", headers=auth(config)).json()["charge_state"]["battery_level"] == 60
    assert len(calls) == 2


def test_authenticated_rate_limit(config):
    with TestClient(create_app(config, httpx.MockTransport(no_network))) as client:
        for _ in range(60):
            assert client.get("/v1/status", headers=auth(config)).status_code == 200
        assert client.get("/v1/status", headers=auth(config)).status_code == 429
        assert client.get("/healthz").status_code == 200


def test_register_is_explicit_and_uses_only_partner_endpoints(config):
    config = real(config)
    calls = []

    def upstream(request):
        calls.append(request)
        assert request.method == "POST"
        if len(calls) == 1:
            assert parse_qs(request.content.decode())["grant_type"] == ["client_credentials"]
            return httpx.Response(200, json={"access_token": "partner-token"})
        assert request.url.path == "/api/1/partner_accounts"
        assert request.headers["Authorization"] == "Bearer partner-token"
        assert request.content == b'{"domain":"relay.example.com"}'
        return httpx.Response(200, json={"response": {}})

    with TestClient(create_app(config, httpx.MockTransport(upstream))) as client:
        assert calls == []
        assert client.post("/v1/tesla/register").status_code == 401
        response = client.post("/v1/tesla/register", headers=auth(config))
        assert response.json() == {"registered": True, "simulated": False}
        assert len(calls) == 2


def test_expired_refresh_token_requires_reconnect(config):
    tesla = connected_tesla(config, lambda req: httpx.Response(400, json={"error": "invalid_grant"}))
    with pytest.raises(UpstreamError) as error:
        tesla.get("/api/1/vehicles")
    assert error.value.status == 401
    assert tesla.store.load() is None
    tesla.http.close()


def test_upstream_retry_after_is_preserved(config):
    tesla = connected_tesla(config, lambda req: httpx.Response(429, headers={"Retry-After": "3600"}))
    with pytest.raises(UpstreamError) as error:
        tesla.get("/api/1/vehicles")
    assert error.value.retry_after == 3600
    tesla.http.close()


def test_timeout_does_not_retry_or_discard_refresh_token(config):
    calls = []

    def timeout(request):
        calls.append(request)
        raise httpx.ReadTimeout("sensitive upstream details", request=request)

    tesla = connected_tesla(config, timeout)
    with pytest.raises(UpstreamError) as error:
        tesla.get("/api/1/vehicles")
    assert error.value.code == "tesla_unavailable"
    assert len(calls) == 1
    assert tesla.store.load()["refresh_token"] == "old-refresh"
    tesla.http.close()
