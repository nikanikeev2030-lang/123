"""Single-device licensing. Deploy behind HTTPS; never expose the database/admin CLI."""
import base64
import hashlib
import json
import os
import re
import secrets
import sqlite3
import time
from pathlib import Path

from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec

LEASE_SECONDS = 900


class Denied(Exception):
    def __init__(self, code, status=403):
        self.code, self.status = code, status


class Store:
    def __init__(self, path):
        self.path = str(path)
        with self.connect() as db:
            db.executescript('''
                CREATE TABLE IF NOT EXISTS licenses(
                    hash TEXT PRIMARY KEY, label TEXT NOT NULL, device TEXT,
                    revoked INTEGER NOT NULL DEFAULT 0, lease_until INTEGER NOT NULL DEFAULT 0);
                CREATE TABLE IF NOT EXISTS nonces(
                    device TEXT, nonce TEXT, expires INTEGER,
                    PRIMARY KEY(device, nonce));
            ''')

    def connect(self):
        db = sqlite3.connect(self.path, timeout=15)
        db.execute('PRAGMA busy_timeout=15000')
        return db

    def issue(self, label):
        key = 'AH-' + secrets.token_hex(24).upper()
        with self.connect() as db:
            db.execute('INSERT INTO licenses(hash,label) VALUES(?,?)', (digest(key.encode()), label))
        return key

    def revoke(self, key):
        with self.connect() as db:
            if db.execute('UPDATE licenses SET revoked=1 WHERE hash=?', (digest(key.encode()),)).rowcount != 1:
                raise ValueError('Unknown license')

    def reset(self, key, now=None):
        # Revocation first; wait until the last lease has expired to prevent two active devices.
        now = int(time.time()) if now is None else now
        with self.connect() as db:
            db.execute('BEGIN IMMEDIATE')
            row = db.execute('SELECT revoked,lease_until FROM licenses WHERE hash=?', (digest(key.encode()),)).fetchone()
            if not row or not row[0] or row[1] > now:
                raise ValueError('Revoke first and wait up to 15 minutes before transfer')
            db.execute('UPDATE licenses SET device=NULL,revoked=0,lease_until=0 WHERE hash=?', (digest(key.encode()),))

    def activate(self, body, now=None):
        now = int(time.time()) if now is None else now
        try:
            key, pub, stamp, nonce, signature = (body[k] for k in ('license','publicKey','timestamp','nonce','signature'))
            if not isinstance(key,str) or not re.fullmatch(r'AH-[A-F0-9]{48}', key):
                raise ValueError()
            if type(stamp) is not int or abs(now-stamp) > 300:
                raise Denied('CLOCK', 400)
            if not isinstance(nonce,str) or not re.fullmatch(r'[a-f0-9]{32}', nonce):
                raise ValueError()
            public_bytes = base64.b64decode(pub, validate=True)
            public = serialization.load_der_public_key(public_bytes)
            if not isinstance(public,ec.EllipticCurvePublicKey) or not isinstance(public.curve,ec.SECP256R1):
                raise ValueError()
            device = digest(public_bytes)
            key_hash = digest(key.encode())
            message = f'arena-license-v1\n{key_hash}\n{device}\n{stamp}\n{nonce}'.encode()
            public.verify(base64.b64decode(signature,validate=True), message, ec.ECDSA(hashes.SHA256()))
        except Denied:
            raise
        except Exception:
            raise Denied('INVALID_REQUEST', 400) from None
        with self.connect() as db:
            db.execute('BEGIN IMMEDIATE')
            row = db.execute('SELECT device,revoked FROM licenses WHERE hash=?',(key_hash,)).fetchone()
            if not row or row[1]:
                raise Denied('LICENSE_INVALID')
            if row[0] is not None and row[0] != device:
                raise Denied('OTHER_DEVICE')
            db.execute('DELETE FROM nonces WHERE expires < ?', (now,))
            try:
                db.execute('INSERT INTO nonces VALUES(?,?,?)',(device,nonce,now+600))
            except sqlite3.IntegrityError:
                raise Denied('REPLAY',409) from None
            db.execute('UPDATE licenses SET device=?,lease_until=? WHERE hash=?',(device,now+LEASE_SECONDS,key_hash))
        return {'device':device,'leaseSeconds':LEASE_SECONDS,'nonce':nonce}


def digest(value):
    return hashlib.sha256(value).hexdigest()


def create_app(store):
    from flask import Flask, request, jsonify
    app = Flask(__name__)
    app.config['MAX_CONTENT_LENGTH'] = 4096

    @app.get('/health')
    def health():
        return {'ok': True}

    @app.post('/v1/activate')
    def activate():
        try:
            result = store.activate(request.get_json(silent=True) or {})
            response = jsonify(result)
            response.headers['Cache-Control'] = 'no-store'
            return response
        except Denied as error:
            return jsonify({'error':error.code}), error.status

    return app


def production():
    path = Path(os.environ.get('LICENSE_DB', '/data/licenses.sqlite'))
    path.parent.mkdir(parents=True, exist_ok=True)
    os.umask(0o077)
    return create_app(Store(path))


if __name__ == '__main__':
    import argparse
    import getpass
    parser = argparse.ArgumentParser(description='Admin CLI; run only on the server.')
    parser.add_argument('command', choices=['issue','revoke','reset'])
    parser.add_argument('--label', default='Buyer')
    parser.add_argument('--db', default=os.environ.get('LICENSE_DB','/data/licenses.sqlite'))
    args = parser.parse_args()
    os.umask(0o077)
    store = Store(args.db)
    if args.command == 'issue':
        print(store.issue(args.label))
    else:
        key = getpass.getpass('License key: ').strip().upper()
        getattr(store,args.command)(key)
        print('OK')
