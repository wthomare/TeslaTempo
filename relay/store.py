import json
import os
import sqlite3
import time
from contextlib import contextmanager

from cryptography.fernet import Fernet


class Store:
    """Only ciphertext for credentials; audit events never contain API payloads."""

    def __init__(self, directory, key):
        directory.mkdir(parents=True, exist_ok=True)
        self.path = directory / "relay.sqlite3"
        self.cipher = Fernet(key)
        with self.connection() as db:
            db.execute("CREATE TABLE IF NOT EXISTS credentials (id INTEGER PRIMARY KEY, value BLOB NOT NULL)")
            db.execute("CREATE TABLE IF NOT EXISTS audit (at INTEGER NOT NULL, event TEXT NOT NULL)")
        os.chmod(self.path, 0o600)
        # A wrong key must fail at startup, never silently discard existing tokens.
        self.load()

    @contextmanager
    def connection(self):
        db = sqlite3.connect(self.path, timeout=10)
        try:
            with db:
                yield db
        finally:
            db.close()

    def save(self, tokens):
        payload = self.cipher.encrypt(json.dumps(tokens).encode())
        with self.connection() as db:
            db.execute("INSERT OR REPLACE INTO credentials VALUES (1, ?)", (payload,))

    def load(self):
        with self.connection() as db:
            row = db.execute("SELECT value FROM credentials WHERE id=1").fetchone()
        return json.loads(self.cipher.decrypt(row[0])) if row else None

    def clear(self):
        with self.connection() as db:
            db.execute("DELETE FROM credentials")

    def audit(self, event):
        with self.connection() as db:
            db.execute("INSERT INTO audit VALUES (?, ?)", (int(time.time()), event))
            db.execute("DELETE FROM audit WHERE rowid NOT IN (SELECT rowid FROM audit ORDER BY rowid DESC LIMIT 1000)")
