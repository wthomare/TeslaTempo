import threading
import time
from email.utils import parsedate_to_datetime

import httpx


TOKEN_URL = "https://fleet-auth.prd.vn.cloud.tesla.com/oauth2/v3/token"
SCOPES = "openid offline_access vehicle_device_data"


class UpstreamError(Exception):
    def __init__(self, status=502, code="tesla_unavailable", retry_after=60):
        self.status = status
        self.code = code
        self.retry_after = retry_after


class Tesla:
    def __init__(self, config, store, transport=None):
        self.config = config
        self.store = store
        self.lock = threading.RLock()
        # No redirects: never forward credentials to an unexpected host.
        self.http = httpx.Client(timeout=httpx.Timeout(15, connect=5), follow_redirects=False, transport=transport)
        self.cache = {}

    def request(self, method, url, **kwargs):
        try:
            response = self.http.request(method, url, **kwargs)
        except httpx.HTTPError:
            raise UpstreamError() from None
        if response.status_code == 429:
            value = response.headers.get("retry-after", "60")
            try:
                delay = int(value)
            except ValueError:
                try:
                    delay = int(parsedate_to_datetime(value).timestamp() - time.time()) + 1
                except (ValueError, TypeError, OverflowError):
                    delay = 60
            raise UpstreamError(429, "tesla_rate_limited", max(60, delay))
        if response.status_code in {401, 403}:
            raise UpstreamError(401, "tesla_authorization_required")
        if response.status_code == 400:
            try:
                error = response.json().get("error")
            except (ValueError, AttributeError):
                error = None
            if isinstance(error, str) and error in {"invalid_grant", "invalid_token", "login_required", "invalid_auth_code"}:
                raise UpstreamError(401, "tesla_authorization_required")
        if response.status_code in {408, 504}:
            raise UpstreamError(503, "vehicle_or_service_unavailable")
        if not response.is_success:
            raise UpstreamError()
        try:
            payload = response.json()
            if not isinstance(payload, dict):
                raise ValueError()
            return payload
        except ValueError:
            raise UpstreamError(502, "invalid_tesla_response") from None

    def persist(self, data):
        try:
            if not all(isinstance(data[k], str) and data[k] for k in ("access_token", "refresh_token")):
                raise ValueError()
            expiry = int(data["expires_in"])
            if expiry <= 0:
                raise ValueError()
            self.store.save({"access_token": data["access_token"], "refresh_token": data["refresh_token"],
                             "expires_at": time.time() + expiry})
        except (KeyError, TypeError, ValueError):
            raise UpstreamError(502, "invalid_token_response") from None

    def exchange(self, code):
        with self.lock:
            data = self.request("POST", TOKEN_URL, data={
                "grant_type": "authorization_code", "client_id": self.config.client_id,
                "client_secret": self.config.client_secret, "code": code,
                "audience": self.config.fleet_url, "redirect_uri": self.config.callback,
            })
            self.persist(data)
            self.cache.clear()
            self.store.audit("oauth_connected")

    def access_token(self):
        # Caller holds the lock across refresh + persistence + request. One worker only.
        data = self.store.load()
        if not data:
            raise UpstreamError(401, "tesla_authorization_required")
        if data["expires_at"] <= time.time() + 60:
            try:
                updated = self.request("POST", TOKEN_URL, data={
                    "grant_type": "refresh_token", "client_id": self.config.client_id,
                    "refresh_token": data["refresh_token"],
                })
                self.persist(updated)
            except UpstreamError as error:
                if error.status == 401:
                    self.disconnect()
                raise
            data = self.store.load()
        return data["access_token"]

    def get(self, path, params=None):
        with self.lock:
            key = (path, tuple(sorted((params or {}).items())))
            cached = self.cache.get(key)
            if cached and cached[0] > time.monotonic():
                return cached[1]
            token = self.access_token()
            try:
                data = self.request("GET", self.config.fleet_url + path, params=params,
                                    headers={"Authorization": "Bearer " + token})
            except UpstreamError as error:
                if error.status == 401:
                    self.disconnect()
                raise
            if len(self.cache) >= 100:
                self.cache.clear()
            self.cache[key] = (time.monotonic() + 30, data)
            return data

    def register(self):
        from urllib.parse import urlsplit
        with self.lock:
            token = self.request("POST", TOKEN_URL, data={
                "grant_type": "client_credentials", "client_id": self.config.client_id,
                "client_secret": self.config.client_secret, "audience": self.config.fleet_url,
                "scope": "vehicle_device_data",
            }).get("access_token")
            if not isinstance(token, str) or not token:
                raise UpstreamError(502, "invalid_token_response")
            self.request("POST", self.config.fleet_url + "/api/1/partner_accounts",
                         headers={"Authorization": "Bearer " + token},
                         json={"domain": urlsplit(self.config.public_url).hostname})
            self.store.audit("partner_registered")

    def disconnect(self):
        with self.lock:
            self.store.clear()
            self.cache.clear()
            self.store.audit("local_disconnect")
