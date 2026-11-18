"""Single-device licensing. Deploy behind HTTPS; never expose the database/admin CLI."""
import calendar
from datetime import datetime, timezone
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
TERMS = {'7d': '7 дней', '1m': '1 месяц', '3m': '3 месяца', '6m': '6 месяцев'}


def term_end(start, term):
    if term not in TERMS: raise ValueError('Invalid license term')
    if term == '7d': return start + 7 * 86400
    date = datetime.fromtimestamp(start, timezone.utc)
    month = date.year * 12 + date.month - 1 + int(term[:-1])
    year, month = month // 12, month % 12 + 1
    return int(date.replace(year=year, month=month,
        day=min(date.day, calendar.monthrange(year, month)[1])).timestamp())



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
                CREATE TABLE IF NOT EXISTS license_terms(
                    hash TEXT PRIMARY KEY, term TEXT NOT NULL,
                    activated_at INTEGER, expires_at INTEGER);
                CREATE TABLE IF NOT EXISTS nonces(
                    device TEXT, nonce TEXT, expires INTEGER,
                    PRIMARY KEY(device, nonce));
            ''')

    def connect(self):
        db = sqlite3.connect(self.path, timeout=15)
        db.execute('PRAGMA busy_timeout=15000')
        return db

    def issue(self, label, term=None):
        if term is not None and term not in TERMS: raise ValueError("Invalid license term")
        key = 'AH-' + secrets.token_hex(24).upper()
        with self.connect() as db:
            db.execute('INSERT INTO licenses(hash,label) VALUES(?,?)', (digest(key.encode()), label))
            if term is not None:
                db.execute('INSERT INTO license_terms(hash,term) VALUES(?,?)',(digest(key.encode()),term))
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
            if not row or not row[0] or row[1] + 60 > now:
                raise ValueError('Revoke first and wait up to 16 minutes before transfer')
            db.execute('UPDATE licenses SET device=NULL,revoked=0,lease_until=0 WHERE hash=?', (digest(key.encode()),))

    def activate(self, body, now=None, action="activate", payload=""):

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
            if action != 'activate':
                message = f'arena-license-v2\n{action}\n{digest(payload.encode())}\n{key_hash}\n{device}\n{stamp}\n{nonce}'.encode()
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
            if action != 'activate' and row[0] is None:
                raise Denied('ACTIVATION_REQUIRED')
            if row[0] is not None and row[0] != device:
                raise Denied('OTHER_DEVICE')
            term_row = db.execute('SELECT term,activated_at,expires_at FROM license_terms WHERE hash=?',(key_hash,)).fetchone()
            expires = None
            if term_row:
                expires = term_row[2]
                if expires is not None and now >= expires: raise Denied('LICENSE_EXPIRED')
                if term_row[1] is None:
                    expires = term_end(now, term_row[0])
                    db.execute('UPDATE license_terms SET activated_at=?,expires_at=? WHERE hash=?',(now,expires,key_hash))
            # Existing APKs require >=60s; protected requests still expire exactly on time.
            lease = min(LEASE_SECONDS, max(60, expires-now)) if expires is not None else LEASE_SECONDS
            db.execute('DELETE FROM nonces WHERE expires < ?', (now,))
            recent=db.execute('SELECT count(*) FROM nonces WHERE device=? AND expires>?',(device,now+540)).fetchone()[0]
            if recent >= 120: raise Denied('RATE_LIMIT',429)
            try:
                db.execute('INSERT INTO nonces VALUES(?,?,?)',(device,nonce,now+600))
            except sqlite3.IntegrityError:
                raise Denied('REPLAY',409) from None
            db.execute('UPDATE licenses SET device=?,lease_until=? WHERE hash=?',(device,min(now+lease,expires) if expires is not None else now+lease,key_hash))
        return {'device':device,'leaseSeconds':lease,'nonce':nonce}


def digest(value):
    return hashlib.sha256(value).hexdigest()


def create_app(store, database=None):
    from flask import Flask, request, jsonify
    app = Flask(__name__)
    app.config['MAX_CONTENT_LENGTH'] = 32768

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

    @app.post('/v1/info')
    @app.post('/v1/analyze')
    def protected_data():
        body=request.get_json(silent=True) or {}
        action=request.path.rsplit('/',1)[-1]
        payload=body.get('payload') if isinstance(body,dict) else None
        if not isinstance(payload,str) or len(payload)>24000:
            return jsonify({'error':'INVALID_REQUEST'}),400
        try:
            permit=store.activate(body,action=action,payload=payload)
            if database is None: raise Denied('DATABASE_UNAVAILABLE',503)
            snapshot=database.get()
            decoded=json.loads(payload)
            if not isinstance(decoded,dict): raise ValueError('Object required')
            result=snapshot.info() if action=='info' else snapshot.analyze(decoded)
            return jsonify({**result,'nonce':permit['nonce']}),200,{'Cache-Control':'no-store'}
        except Denied as error:
            return jsonify({'error':error.code}),error.status
        except (ValueError,TypeError,KeyError):
            return jsonify({'error':'INVALID_REQUEST'}),400
        except OSError:
            return jsonify({'error':'DATABASE_UNAVAILABLE'}),503

    from admin_panel import register_admin
    register_admin(app,store)
    return app


def production():
    path = Path(os.environ.get('LICENSE_DB', '/data/licenses.sqlite'))
    path.parent.mkdir(parents=True, exist_ok=True)
    os.umask(0o077)
    from database import Database
    database=Database(path.parent/'database.json.gz',Path(__file__).parent/'seed')
    return create_app(Store(path),database)


if __name__ == '__main__':
    import argparse
    import getpass
    parser = argparse.ArgumentParser(description='Admin CLI; run only on the server.')
    parser.add_argument('command', choices=['issue','revoke','reset'])
    parser.add_argument('--label', default='Buyer')
    parser.add_argument('--term', choices=TERMS)
    parser.add_argument('--db', default=os.environ.get('LICENSE_DB','/data/licenses.sqlite'))
    args = parser.parse_args()
    os.umask(0o077)
    store = Store(args.db)
    if args.command == 'issue':
        print(store.issue(args.label,args.term))
    else:
        key = getpass.getpass('License key: ').strip().upper()
        getattr(store,args.command)(key)
        print('OK')
