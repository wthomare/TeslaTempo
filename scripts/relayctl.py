#!/usr/bin/env python3
"""Local operator CLI. Reads a token file, never puts it in process arguments."""
import argparse
import json
from pathlib import Path
from urllib.error import HTTPError
from urllib.parse import urlsplit
from urllib.request import Request


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=["status", "vehicles", "charge-state", "connect", "register", "disconnect"])
    parser.add_argument("--url", default="http://localhost:8080")
    parser.add_argument("--token-file", default="secrets/relay_api_token")
    parser.add_argument("--vehicle", default="demo")
    args = parser.parse_args()
    base = urlsplit(args.url)
    if base.scheme != "https" and not (base.scheme == "http" and base.hostname in {"localhost", "127.0.0.1"}):
        parser.error("HTTPS requis hors localhost")
    if base.username or base.password or base.query or base.fragment or base.path not in {"", "/"}:
        parser.error("L'URL doit être une origine sans chemin ni identifiants")
    if not args.vehicle.isascii() or not args.vehicle.isalnum():
        parser.error("Identifiant véhicule invalide")
    routes = {
        "status": ("GET", "/v1/status"), "vehicles": ("GET", "/v1/vehicles"),
        "charge-state": ("GET", f"/v1/vehicles/{args.vehicle}/charge-state"),
        "connect": ("POST", "/v1/tesla/connect"), "register": ("POST", "/v1/tesla/register"),
        "disconnect": ("DELETE", "/v1/tesla/connection"),
    }
    method, path = routes[args.command]
    token = Path(args.token_file).read_text().strip()
    request = Request(args.url.rstrip("/") + path, method=method, headers={"Authorization": "Bearer " + token})
    # Reject redirects to avoid forwarding a bearer token to another origin.
    from urllib.request import HTTPRedirectHandler, ProxyHandler, build_opener

    class NoRedirect(HTTPRedirectHandler):
        def redirect_request(self, req, fp, code, msg, headers, newurl):
            return None

    try:
        handlers = [NoRedirect()]
        if base.hostname in {"localhost", "127.0.0.1"}:
            handlers.append(ProxyHandler({}))
        with build_opener(*handlers).open(request, timeout=30) as response:
            result = json.load(response)
    except HTTPError as error:
        raise SystemExit(f"Erreur HTTP {error.code}. Vérifier le mode, l'authentification et la connexion Tesla.") from None
    print(json.dumps(result, indent=2, ensure_ascii=False))


if __name__ == "__main__":
    main()
