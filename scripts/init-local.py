#!/usr/bin/env python3
"""Prepare a personal instance, without printing or overwriting any secret."""
import base64
import os
from pathlib import Path
import secrets
import subprocess


def main():
    if os.getuid() == 0:
        raise SystemExit("Exécuter avec un compte non-root dédié : les fichiers et le conteneur utiliseront son UID/GID.")
    os.umask(0o077)
    root = Path(__file__).resolve().parent.parent
    secret_dir = root / "secrets"
    for folder in (secret_dir, root / "data"):
        folder.mkdir(mode=0o700, exist_ok=True)
    values = {
        "relay_api_token": secrets.token_urlsafe(48),
        "token_encryption_key": base64.urlsafe_b64encode(secrets.token_bytes(32)).decode(),
        "tesla_client_secret": "",  # Filled by the operator only when switching to Tesla mode.
    }
    for name, value in values.items():
        path = secret_dir / name
        if not path.exists():
            with path.open("x") as f:
                f.write(value + "\n")
    private, public = secret_dir / "tesla_private_key.pem", secret_dir / "tesla_public_key.pem"
    if private.exists() != public.exists():
        raise SystemExit("Paire de clés incomplète : restaurer les fichiers manquants, aucune clé remplacée.")
    if not private.exists():
        subprocess.run(["openssl", "genpkey", "-algorithm", "EC", "-pkeyopt", "ec_paramgen_curve:P-256", "-out", str(private)], check=True)
        subprocess.run(["openssl", "pkey", "-in", str(private), "-pubout", "-out", str(public)], check=True)
    env = root / ".env"
    if not env.exists():
        template = (root / ".env.example").read_text()
        template = template.replace("RELAY_UID=1000", f"RELAY_UID={os.getuid()}")
        template = template.replace("RELAY_GID=1000", f"RELAY_GID={os.getgid()}")
        env.write_text(template)
    print("Instance préparée : secrets/, data/ et .env. Aucun secret affiché ou remplacé.")
    print("La clé privée reste hors du conteneur de cette V1 en lecture seule.")


if __name__ == "__main__":
    main()
