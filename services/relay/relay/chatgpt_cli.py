#!/usr/bin/env python3
"""Personal ChatGPT OAuth setup. Run login on the computer hosting the browser."""
import argparse
import hmac
import json
import logging
import os
import sys
import time
import webbrowser
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path
from urllib.parse import parse_qs, urlsplit

from cryptography.fernet import Fernet
from relay.chatgpt import ChatGPT, ChatGPTError, ChatGPTStore, atomic_private, checked_record


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('command', choices=['login', 'status', 'models', 'disconnect', 'export', 'import', 'select'])
    p.add_argument('--directory', type=Path, default=Path.home() / '.config/teslatempo/chatgpt')
    p.add_argument('--key-file', type=Path, help='Existing relay encryption key for a NAS state directory')
    p.add_argument('--account', help='Saved registration ID; omit only for a new registration')
    p.add_argument('--file', type=Path, help='Private credential transfer file (export/import)')
    args = p.parse_args()
    logging.getLogger('httpx').setLevel(logging.WARNING)
    logging.getLogger('httpcore').setLevel(logging.WARNING)
    args.directory.mkdir(mode=0o700, parents=True, exist_ok=True)
    keyfile = args.key_file or args.directory / 'local.key'
    if not keyfile.exists():
        if args.key_file: p.error('The supplied encryption key file does not exist')
        fd = os.open(keyfile, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
        with os.fdopen(fd, 'wb') as f: f.write(Fernet.generate_key())
    store = ChatGPTStore(args.directory, keyfile.read_bytes().strip())
    client = ChatGPT(store)
    try:
        if args.command == 'login':
            login(client, args.account)
        elif args.command == 'status': print(json.dumps(client.status(), indent=2))
        elif args.command == 'models': print(json.dumps(client.models(), indent=2))
        elif args.command == 'disconnect': print(json.dumps(client.disconnect()))
        elif args.command == 'select':
            with store.locked():
                state = store.load()
                if args.account not in state['accounts']: raise ChatGPTError('unknown_chatgpt_account')
                state['active'] = args.account; store.save(state)
            print('Account selected; no inference requested.')
        elif args.command == 'export':
            if not args.file: p.error('--file is required')
            if args.file.exists(): p.error('Refusing to overwrite an existing transfer file')
            with store.locked():
                state = store.load(); record = state['accounts'].get(args.account or state['active'])
                checked_record(record)
                atomic_private(args.file, json.dumps(record).encode())
            print('Private transfer file written. Transfer securely, then remove it. The destination owns refreshes; do not use this local session concurrently.')
        elif args.command == 'import':
            if not args.file or not args.file.is_file(): p.error('--file must name a private transfer file')
            if args.file.stat().st_mode & 0o077: p.error('Transfer file must be owner-only (chmod 600)')
            if args.file.stat().st_size > 131072: p.error('Transfer file too large')
            record = checked_record(json.loads(args.file.read_text()))
            with store.locked():
                state = store.load(); previous = state['accounts'].get(record['client_id'])
                if previous and previous['subject'] != record['subject']: raise ChatGPTError('account_mismatch')
                state['accounts'][record['client_id']] = record
                state['active'] = record['client_id']; store.save(state)
            print('Credentials imported; destination host ID preserved. Remove the transfer file.')
    finally: client.http.close()


def login(client, account):
    outcome = []
    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *args): pass
        def do_GET(self):
            parsed = urlsplit(self.path)
            # Invalid callbacks do not consume a legitimate pending attempt.
            q = parse_qs(parsed.query)
            if len(self.path) > 16384 or parsed.path != '/auth/callback' or len(q.get('state', [])) != 1 or not hmac.compare_digest(q['state'][0], pending['state']):
                self.send_error(400, 'Invalid callback'); return
            try:
                result = client.finish(parsed.query, pending)
                message = 'Connexion enregistree. Vous pouvez fermer cette fenetre.'
                outcome.append(None if result['plan_enabled'] else 'chatgpt_plan_consent_required')
            except ChatGPTError as e:
                message = 'Connexion non terminee. Consultez le terminal TeslaTempo.'; outcome.append(e.code)
            self.send_response(200)
            self.send_header('Content-Type', 'text/plain; charset=utf-8')
            self.send_header('Cache-Control', 'no-store')
            self.send_header('Referrer-Policy', 'no-referrer')
            self.send_header('Content-Security-Policy', "default-src 'none'")
            self.end_headers(); self.wfile.write(message.encode())
    with HTTPServer(('127.0.0.1', 0), Handler) as server:
        server.timeout = 1
        url, pending = client.begin(f'http://127.0.0.1:{server.server_port}/auth/callback', account)
        print('Continue with ChatGPT — ouverture du navigateur local. Delai : 10 minutes.', flush=True)
        if not webbrowser.open(url): raise ChatGPTError('local_browser_unavailable')
        while not outcome and time.monotonic() < pending['expires_at']: server.handle_request()
    if not outcome: raise ChatGPTError('chatgpt_login_timeout')
    if outcome[0]: raise ChatGPTError(outcome[0])
    print('Connexion validee. Aucun appel de generation effectue.')


if __name__ == '__main__':
    try: main()
    except ChatGPTError as e: raise SystemExit('ChatGPT: ' + e.code) from None
    except (OSError, ValueError, KeyError): raise SystemExit('Configuration ou fichier invalide ; aucun detail sensible affiche.') from None
