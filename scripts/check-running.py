#!/usr/bin/env python3
"""Smoke-test the local Compose instance. No external network calls."""
import json
from pathlib import Path
from urllib.error import HTTPError
from urllib.request import ProxyHandler, Request, build_opener


def main():
    opener = build_opener(ProxyHandler({}))
    token = Path("secrets/relay_api_token").read_text().strip()

    def get(path, authenticated=False):
        headers = {"Authorization": "Bearer " + token} if authenticated else {}
        return opener.open(Request("http://127.0.0.1:8080" + path, headers=headers), timeout=5)

    with get("/healthz") as response:
        assert json.load(response)["status"] == "ok"
    try:
        get("/v1/status")
        raise AssertionError("Unauthenticated API access must fail")
    except HTTPError as error:
        assert error.code == 401
    with get("/v1/status", True) as response:
        status = json.load(response)
        assert status["mode"] == "mock" and status["vehicle_commands_enabled"] is False
    with get("/.well-known/appspecific/com.tesla.3p.public-key.pem") as response:
        assert response.read() == Path("secrets/tesla_public_key.pem").read_bytes()
    with get("/v1/vehicles/demo/charge-state", True) as response:
        assert json.load(response)["simulated"] is True
    print("Compose smoke test: health, authentication, public key and mock data OK.")


if __name__ == "__main__":
    main()
