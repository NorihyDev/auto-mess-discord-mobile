"""Small HTTPS-proxy-ready license service. Run behind a TLS reverse proxy."""
import argparse
import base64
from contextlib import contextmanager
import hashlib
import hmac
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
from pathlib import Path
import secrets
import sqlite3
import time

from cryptography.exceptions import InvalidSignature, UnsupportedAlgorithm
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec


SCHEMA = """
CREATE TABLE IF NOT EXISTS licenses (
  code_hash TEXT PRIMARY KEY, label TEXT NOT NULL, created_at INTEGER NOT NULL,
  revoked_at INTEGER, device_id TEXT, public_key TEXT,
  model TEXT, first_seen INTEGER, last_seen INTEGER
);
CREATE TABLE IF NOT EXISTS nonces (
  nonce TEXT PRIMARY KEY, created_at INTEGER NOT NULL
);
"""


def digest(value):
    return hashlib.sha256(value.encode('utf-8')).hexdigest()


def device_id(public_key):
    return digest(public_key)[:16]


def verify(public_key, action, code, nonce, signature):
    try:
        key = serialization.load_der_public_key(base64.b64decode(public_key, validate=True))
        if not isinstance(key, ec.EllipticCurvePublicKey) or not isinstance(key.curve, ec.SECP256R1):
            return False
        key.verify(base64.b64decode(signature, validate=True),
                   f'{action}\n{code}\n{nonce}'.encode(), ec.ECDSA(hashes.SHA256()))
        return True
    except (ValueError, TypeError, InvalidSignature, UnsupportedAlgorithm):
        return False


class Handler(BaseHTTPRequestHandler):
    server_version = 'DMA-License/1'

    def respond(self, status, body):
        data = json.dumps(body).encode()
        self.send_response(status)
        self.send_header('Content-Type', 'application/json; charset=utf-8')
        self.send_header('Cache-Control', 'no-store')
        self.send_header('X-Content-Type-Options', 'nosniff')
        self.send_header('Content-Length', str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def body(self):
        size = int(self.headers.get('Content-Length', '0'))
        if size < 1 or size > 8192:
            raise ValueError('invalid body size')
        value = json.loads(self.rfile.read(size))
        if not isinstance(value, dict):
            raise ValueError('invalid body')
        return value

    def admin(self):
        expected = self.server.admin_token
        offered = self.headers.get('Authorization', '')
        return hmac.compare_digest(offered, 'Bearer ' + expected)

    def do_GET(self):
        if self.path == '/admin':
            data = (Path(__file__).parent / 'admin.html').read_bytes()
            self.send_response(200)
            self.send_header('Content-Type', 'text/html; charset=utf-8')
            self.send_header('Cache-Control', 'no-store')
            self.send_header('Content-Security-Policy', "default-src 'none'; style-src 'unsafe-inline'; script-src 'unsafe-inline'; connect-src 'self'; base-uri 'none'; form-action 'none'")
            self.send_header('Content-Length', str(len(data)))
            self.end_headers()
            return self.wfile.write(data)
        if self.path == '/health':
            return self.respond(200, {'ok': True})
        if self.path != '/admin/api/licenses':
            return self.respond(404, {'error': 'not found'})
        if not self.admin():
            return self.respond(401, {'error': 'unauthorized'})
        with self.server.connect() as db:
            rows = db.execute('SELECT code_hash, label, created_at, revoked_at, device_id, model, first_seen, last_seen FROM licenses ORDER BY created_at DESC').fetchall()
        return self.respond(200, {'licenses': [dict(zip(('id', 'label', 'created_at', 'revoked_at', 'device_id', 'model', 'first_seen', 'last_seen'), r)) for r in rows]})

    def do_POST(self):
        try:
            body = self.body()
            if self.path.startswith('/admin/api/'):
                if not self.admin():
                    return self.respond(401, {'error': 'unauthorized'})
                return self.admin_post(body)
            if self.path in ('/api/activate', '/api/check'):
                return self.device_post(body)
            return self.respond(404, {'error': 'not found'})
        except (ValueError, KeyError, TypeError):
            return self.respond(400, {'error': 'invalid request'})

    def admin_post(self, body):
        if self.path == '/admin/api/create':
            label = str(body.get('label', '')).strip()[:80]
            if not label:
                raise ValueError('label required')
            code = 'DMA-' + '-'.join(secrets.token_hex(3).upper() for _ in range(4))
            with self.server.connect() as db:
                db.execute('INSERT INTO licenses (code_hash, label, created_at) VALUES (?, ?, ?)', (digest(code), label, int(time.time())))
            return self.respond(201, {'key': code})
        if self.path == '/admin/api/revoke':
            license_id = str(body['id'])
            if len(license_id) != 64 or any(c not in '0123456789abcdef' for c in license_id):
                raise ValueError('invalid id')
            with self.server.connect() as db:
                changed = db.execute('UPDATE licenses SET revoked_at=? WHERE code_hash=? AND revoked_at IS NULL', (int(time.time()), license_id)).rowcount
            return self.respond(200 if changed else 404, {'revoked': bool(changed)})
        return self.respond(404, {'error': 'not found'})

    def device_post(self, body):
        code = str(body['key']).strip().upper()
        public_key = str(body['public_key'])
        nonce = str(body['nonce'])
        signature = str(body['signature'])
        if len(code) > 80 or len(public_key) > 1024 or len(nonce) < 16 or len(nonce) > 100 or len(signature) > 512:
            raise ValueError('invalid credentials')
        action = 'activate' if self.path.endswith('activate') else 'check'
        if not verify(public_key, action, code, nonce, signature):
            return self.respond(403, {'valid': False, 'reason': 'signature'})
        now = int(time.time())
        with self.server.connect() as db:
            db.execute('BEGIN IMMEDIATE')
            db.execute('DELETE FROM nonces WHERE created_at < ?', (now - 3600,))
            try:
                db.execute('INSERT INTO nonces VALUES (?, ?)', (nonce, now))
            except sqlite3.IntegrityError:
                return self.respond(403, {'valid': False, 'reason': 'replay'})
            row = db.execute('SELECT revoked_at, public_key FROM licenses WHERE code_hash=?', (digest(code),)).fetchone()
            if row is None or row[0] is not None:
                return self.respond(403, {'valid': False, 'reason': 'unknown or revoked'})
            if row[1] and row[1] != public_key:
                return self.respond(403, {'valid': False, 'reason': 'already bound to another phone'})
            if not row[1] and action != 'activate':
                return self.respond(403, {'valid': False, 'reason': 'activation required'})
            model = str(body.get('model', 'Unknown'))[:100]
            db.execute('UPDATE licenses SET device_id=?, public_key=?, model=?, first_seen=COALESCE(first_seen, ?), last_seen=? WHERE code_hash=?',
                       (device_id(public_key), public_key, model, now, now, digest(code)))
        return self.respond(200, {'valid': True, 'device_id': device_id(public_key)})

    def log_message(self, fmt, *args):
        pass  # Never log license keys or device identifiers.


class Server(ThreadingHTTPServer):
    def __init__(self, address, database, admin_token):
        super().__init__(address, Handler)
        self.database = database
        self.admin_token = admin_token
        with self.connect() as db:
            db.executescript(SCHEMA)

    @contextmanager
    def connect(self):
        db = sqlite3.connect(self.database, timeout=10)
        db.execute('PRAGMA busy_timeout=10000')
        try:
            with db:
                yield db
        finally:
            db.close()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--host', default='127.0.0.1')
    parser.add_argument('--port', type=int, default=8765)
    parser.add_argument('--db', default='licenses.sqlite3')
    args = parser.parse_args()
    token = os.environ.get('DMA_ADMIN_TOKEN', '')
    if len(token) < 32:
        parser.error('set DMA_ADMIN_TOKEN to a random string of at least 32 characters')
    Server((args.host, args.port), args.db, token).serve_forever()


if __name__ == '__main__':
    main()
