import hashlib
import hmac
import logging
import secrets
import threading
import time
from collections import deque
from contextlib import asynccontextmanager
from urllib.parse import urlencode

from fastapi import Depends, FastAPI, HTTPException, Request
from fastapi.responses import HTMLResponse, JSONResponse, RedirectResponse, Response

from relay import __version__
from relay.config import Config
from relay.store import Store
from relay.tesla import SCOPES, Tesla, UpstreamError


def digest(value):
    return hashlib.sha256(value.encode()).hexdigest()


def create_app(config=None, transport=None):
    config = config or Config.from_env()
    config.validate()
    store = Store(config.data_dir, config.encryption_key)
    tesla = Tesla(config, store, transport)
    lock = threading.RLock()
    tickets, flows = {}, {}
    requests = deque()
    # HTTP client INFO logs contain URLs (including codes). Never enable them here.
    logging.getLogger("httpx").setLevel(logging.WARNING)
    logging.getLogger("httpcore").setLevel(logging.WARNING)

    @asynccontextmanager
    async def lifespan(app):
        yield
        tesla.http.close()

    app = FastAPI(title="Tesla Tempo Relay", version=__version__, docs_url=None,
                  redoc_url=None, openapi_url=None, lifespan=lifespan)
    app.state.tesla = tesla

    @app.middleware("http")
    async def headers(request, call_next):
        response = await call_next(request)
        response.headers["Cache-Control"] = "no-store"
        response.headers["Referrer-Policy"] = "no-referrer"
        response.headers["X-Content-Type-Options"] = "nosniff"
        response.headers["Content-Security-Policy"] = "default-src 'none'; frame-ancestors 'none'"
        if config.secure_cookie:
            response.headers["Strict-Transport-Security"] = "max-age=31536000"
        return response

    @app.exception_handler(UpstreamError)
    async def upstream_error(request, error):
        return JSONResponse({"error": error.code}, status_code=error.status,
                            headers={"Retry-After": str(error.retry_after)} if error.status == 429 else None)

    def authenticated(request: Request):
        provided = request.headers.get("Authorization", "")
        if not hmac.compare_digest(provided.encode(), ("Bearer " + config.api_token).encode()):
            raise HTTPException(401, "relay_authentication_required", headers={"WWW-Authenticate": "Bearer"})
        with lock:
            now = time.monotonic()
            while requests and requests[0] <= now - 60:
                requests.popleft()
            if len(requests) >= 60:
                raise HTTPException(429, "relay_rate_limited", headers={"Retry-After": "60"})
            requests.append(now)

    protected = [Depends(authenticated)]

    @app.get("/healthz")
    def health():
        with store.connection() as db:
            db.execute("SELECT 1")
        return {"status": "ok", "version": __version__}

    @app.get("/.well-known/appspecific/com.tesla.3p.public-key.pem")
    def public_key():
        return Response(config.public_key, media_type="application/x-pem-file")

    @app.get("/v1/status", dependencies=protected)
    def status():
        return {"mode": config.mode, "version": __version__, "vehicle_commands_enabled": False,
                "tesla_connected": bool(store.load()) if config.mode == "tesla" else False,
                "simulated": config.mode == "mock"}

    @app.post("/v1/tesla/register", dependencies=protected)
    def register():
        if config.mode == "mock":
            return {"simulated": True, "registered": False}
        tesla.register()
        return {"registered": True, "simulated": False}

    @app.post("/v1/tesla/connect", dependencies=protected)
    def connect():
        if config.mode == "mock":
            raise HTTPException(409, "oauth_unavailable_in_mock")
        with lock:
            now = time.monotonic()
            for collection in (tickets, flows):
                for key in list(collection):
                    if collection[key][0] <= now:
                        del collection[key]
            if len(tickets) + len(flows) >= 5:
                raise HTTPException(429, "too_many_oauth_attempts")
            ticket = secrets.token_urlsafe(32)
            tickets[digest(ticket)] = (now + 120, None)
        # This short-lived, single-use URL is opened in the user's system browser.
        return {"launch_url": config.public_url + "/oauth/launch?" + urlencode({"ticket": ticket}), "expires_in": 120}

    @app.get("/oauth/launch")
    def launch(ticket: str = ""):
        with lock:
            item = tickets.pop(digest(ticket), None)
            if not item or item[0] <= time.monotonic():
                raise HTTPException(400, "invalid_oauth_ticket")
            state, browser = secrets.token_urlsafe(32), secrets.token_urlsafe(32)
            flows[digest(state)] = (time.monotonic() + 600, digest(browser))
        url = "https://auth.tesla.com/oauth2/v3/authorize?" + urlencode({
            "response_type": "code", "client_id": config.client_id,
            "redirect_uri": config.callback, "scope": SCOPES, "state": state,
        })
        response = RedirectResponse(url, status_code=302)
        response.set_cookie("tesla_oauth", browser, max_age=600, httponly=True,
                            secure=config.secure_cookie, samesite="lax", path="/oauth")
        return response

    @app.get("/oauth/callback")
    def callback(request: Request, state: str = "", code: str = "", error: str = ""):
        with lock:
            item = flows.get(digest(state))
            browser = digest(request.cookies.get("tesla_oauth", ""))
            if not item or item[0] <= time.monotonic() or not hmac.compare_digest(item[1], browser):
                raise HTTPException(400, "invalid_oauth_state")
            del flows[digest(state)]
            if not error and code:
                # Keep disconnect from racing an in-flight code exchange.
                tesla.exchange(code)
        if error or not code:
            response = HTMLResponse("Connexion Tesla annulée ou refusée. Recommencez depuis votre application.", status_code=400)
        else:
            response = HTMLResponse("Connexion Tesla enregistrée. Vous pouvez fermer cette fenêtre.")
        response.delete_cookie("tesla_oauth", path="/oauth", secure=config.secure_cookie, httponly=True, samesite="lax")
        return response

    @app.delete("/v1/tesla/connection", dependencies=protected)
    def disconnect():
        # Serialize callback/refresh with disconnect; invalidate outstanding flows too.
        with lock, tesla.lock:
            tickets.clear()
            flows.clear()
            tesla.disconnect()
        return {"disconnected_locally": True, "tesla_consent_revoked": False}

    def vehicles():
        data = tesla.get("/api/1/vehicles", {"page": 1})
        rows = data.get("response")
        if not isinstance(rows, list):
            raise UpstreamError(502, "invalid_tesla_response")
        return rows

    @app.get("/v1/vehicles", dependencies=protected)
    def list_vehicles():
        if config.mode == "mock":
            return {"simulated": True, "vehicles": [{"id": "demo", "name": "Model Y — démonstration", "state": "online"}]}
        return {"simulated": False, "vehicles": [
            {"id": str(row["id"]), "name": row.get("display_name"), "state": row.get("state")}
            for row in vehicles() if isinstance(row, dict) and "id" in row], "page": 1}

    @app.get("/v1/vehicles/{vehicle_id}/charge-state", dependencies=protected)
    def charge_state(vehicle_id: str):
        if config.mode == "mock":
            if vehicle_id != "demo":
                raise HTTPException(404, "vehicle_not_found")
            return {"simulated": True, "sampled_at": None, "charge_state": {
                "battery_level": 55, "charge_limit_soc": 80, "charging_state": "Stopped", "charge_current_request": 10}}
        if not vehicle_id.isascii() or not vehicle_id.isdigit() or len(vehicle_id) > 30:
            raise HTTPException(404, "vehicle_not_found")
        if vehicle_id not in {str(row.get("id")) for row in vehicles() if isinstance(row, dict)}:
            raise HTTPException(404, "vehicle_not_found")
        data = tesla.get(f"/api/1/vehicles/{vehicle_id}/vehicle_data", {"endpoints": "charge_state;charge_schedule_data"})
        row = data.get("response")
        if not isinstance(row, dict):
            raise UpstreamError(502, "invalid_tesla_response")
        return {"simulated": False, "charge_state": row.get("charge_state"),
                "charge_schedule_data": row.get("charge_schedule_data")}

    return app
