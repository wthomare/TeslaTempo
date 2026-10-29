import os
from dataclasses import dataclass
from pathlib import Path
from urllib.parse import urlsplit

from cryptography.fernet import Fernet
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import ec


@dataclass(frozen=True)
class Config:
    mode: str
    public_url: str
    api_token: str
    encryption_key: bytes
    public_key: bytes
    data_dir: Path
    client_id: str = ""
    client_secret: str = ""
    region: str = "eu"

    @property
    def fleet_url(self):
        return f"https://fleet-api.prd.{self.region}.vn.cloud.tesla.com"

    @property
    def callback(self):
        return self.public_url + "/oauth/callback"

    @property
    def secure_cookie(self):
        return self.public_url.startswith("https://")

    def validate(self):
        if self.mode not in {"mock", "tesla"} or self.region not in {"eu", "na"}:
            raise ValueError("RELAY_MODE ou TESLA_REGION invalide (mock/tesla, eu/na)")
        u = urlsplit(self.public_url)
        local_mock = self.mode == "mock" and u.hostname in {"localhost", "127.0.0.1"}
        if (u.scheme != "https" and not (local_mock and u.scheme == "http")) or not u.hostname:
            raise ValueError("PUBLIC_BASE_URL doit utiliser HTTPS (HTTP local autorisé en mock)")
        if u.username or u.password or u.query or u.fragment or u.path:
            raise ValueError("PUBLIC_BASE_URL doit être une origine sans chemin ni identifiants")
        if len(self.api_token) < 32 or not self.api_token.isascii() or any(c.isspace() for c in self.api_token):
            raise ValueError("Le jeton du relais doit contenir au moins 32 caractères ASCII sans espaces")
        Fernet(self.encryption_key)
        key = serialization.load_pem_public_key(self.public_key)
        if not isinstance(key, ec.EllipticCurvePublicKey) or not isinstance(key.curve, ec.SECP256R1):
            raise ValueError("La clé publique Tesla doit être une clé EC P-256")
        if self.mode == "tesla" and (not self.client_id or not self.client_secret):
            raise ValueError("TESLA_CLIENT_ID et le fichier du secret Tesla sont requis")

    @classmethod
    def from_env(cls):
        def read(name, default):
            return Path(os.environ.get(name, default)).read_text().strip()

        mode = os.environ.get("RELAY_MODE", "mock")
        result = cls(
            mode=mode,
            public_url=os.environ.get("PUBLIC_BASE_URL", "http://localhost:8080").rstrip("/"),
            api_token=read("RELAY_API_TOKEN_FILE", "/run/secrets/relay_api_token"),
            encryption_key=read("TOKEN_ENCRYPTION_KEY_FILE", "/run/secrets/token_encryption_key").encode(),
            public_key=Path(os.environ.get("TESLA_PUBLIC_KEY_FILE", "/run/secrets/tesla_public_key.pem")).read_bytes(),
            data_dir=Path(os.environ.get("DATA_DIR", "/data")),
            client_id=os.environ.get("TESLA_CLIENT_ID", ""),
            client_secret=read("TESLA_CLIENT_SECRET_FILE", "/run/secrets/tesla_client_secret") if mode == "tesla" else "",
            region=os.environ.get("TESLA_REGION", "eu"),
        )
        result.validate()
        return result
