import sqlite3
import tempfile
import time
import unittest
from datetime import datetime, timezone
from pathlib import Path
from unittest.mock import patch
from cryptography.hazmat.primitives.asymmetric import ec
from server import Store, Denied, create_app, digest, term_end, TERMS
from test_server import proof
from test_database import data_proof


class TermTests(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory();self.addCleanup(self.tmp.cleanup)
        self.store=Store(Path(self.tmp.name)/'licenses.sqlite')
        self.phone=ec.generate_private_key(ec.SECP256R1())
        self.app=create_app(self.store);self.panel=self.app.extensions['arena_admin']
        self.now=1800000000

    def dates(self,key):
        with self.store.connect() as db:
            return db.execute('SELECT term,activated_at,expires_at FROM license_terms WHERE hash=?',(digest(key.encode()),)).fetchone()

    def test_all_terms_begin_only_at_first_activation_and_never_extend(self):
        for term in TERMS:
            key,_=self.panel.action('issue','','Buyer',term)
            self.assertEqual(self.dates(key),(term,None,None))
            self.store.activate(proof(key,self.phone,self.now),self.now)
            expected=(term,self.now,term_end(self.now,term))
            self.assertEqual(self.dates(key),expected)
            Store(self.store.path).activate(proof(key,self.phone,self.now+100),self.now+100)
            self.assertEqual(self.dates(key),expected)

    def test_calendar_months_clamp_end_of_month_and_leap_year(self):
        def stamp(s):return int(datetime.fromisoformat(s).replace(tzinfo=timezone.utc).timestamp())
        for start,term,end in [('2028-01-31T12:45:00','1m','2028-02-29T12:45:00'),('2027-01-31T12:45:00','1m','2027-02-28T12:45:00'),('2026-11-30T12:45:00','3m','2027-02-28T12:45:00'),('2026-08-31T12:45:00','6m','2027-02-28T12:45:00')]:
            self.assertEqual(term_end(stamp(start),term),stamp(end))

    def test_exact_expiry_blocks_activation_info_and_analysis(self):
        key=self.store.issue('buyer','7d');self.store.activate(proof(key,self.phone,self.now),self.now)
        expiry=term_end(self.now,'7d')
        self.assertEqual(self.store.activate(proof(key,self.phone,expiry-1),expiry-1)['leaseSeconds'],60)
        client=self.app.test_client()
        with patch('time.time',return_value=expiry):
            for action in ['activate','info','analyze']:
                body=proof(key,self.phone,expiry) if action=='activate' else data_proof(key,self.phone,action,{})
                response=client.post('/v1/'+action,json=body)
                self.assertEqual(response.status_code,403)
                self.assertEqual(response.json['error'],'LICENSE_EXPIRED')
        self.assertEqual(self.dates(key)[2],expiry)

    def test_transfer_preserves_remaining_time_not_full_new_term(self):
        with patch('time.time',return_value=self.now):
            key,_=self.panel.action('issue','','Buyer','3m')
        self.store.activate(proof(key,self.phone,self.now),self.now)
        original=self.dates(key)
        with patch('time.time',return_value=self.now+2000):
            new,_=self.panel.action('transfer',digest(key.encode()),'')
        self.assertEqual(self.dates(new),original)
        other=ec.generate_private_key(ec.SECP256R1())
        self.store.activate(proof(new,other,self.now+4000),self.now+4000)
        self.assertEqual(self.dates(new),original)
        with self.assertRaises(Denied):self.store.activate(proof(key,self.phone,self.now+4000),self.now+4000)

    def test_unused_transfer_starts_on_new_phone_and_expired_transfer_rejected(self):
        key,_=self.panel.action('issue','','Buyer','7d')
        new,_=self.panel.action('transfer',digest(key.encode()),'')
        self.assertEqual(self.dates(new),('7d',None,None))
        self.store.activate(proof(new,self.phone,self.now),self.now)
        with patch('time.time',return_value=self.now+8*86400):
            with self.assertRaises(ValueError):self.panel.action('transfer',digest(new.encode()),'')

    def test_invalid_term_cannot_create_a_license(self):
        for term in ['',None,'forever','12m','-1']:
            with self.assertRaises(ValueError):self.panel.action('issue','','Buyer',term)
        with self.store.connect() as db:self.assertEqual(db.execute('SELECT count(*) FROM licenses').fetchone()[0],0)

    def test_legacy_schema_and_keys_are_preserved(self):
        path=Path(self.tmp.name)/'old.sqlite'
        with sqlite3.connect(path) as db:
            db.execute('CREATE TABLE licenses(hash TEXT PRIMARY KEY,label TEXT NOT NULL,device TEXT,revoked INTEGER NOT NULL DEFAULT 0,lease_until INTEGER NOT NULL DEFAULT 0)')
            key='AH-'+'A'*48
            db.execute('INSERT INTO licenses(hash,label) VALUES(?,?)',(digest(key.encode()),'old'))
        old=Store(path);old.activate(proof(key,self.phone,self.now),self.now)
        old.activate(proof(key,self.phone,self.now+365*86400),self.now+365*86400)

    def test_forgery_does_not_start_term_and_cli_reset_does_not_extend_it(self):
        key=self.store.issue('Buyer','1m');bad=proof(key,self.phone,self.now);bad['signature']='bad'
        with self.assertRaises(Denied):self.store.activate(bad,self.now)
        self.assertEqual(self.dates(key),('1m',None,None))
        self.store.activate(proof(key,self.phone,self.now),self.now);original=self.dates(key)
        self.store.revoke(key);self.store.reset(key,self.now+2000)
        self.store.activate(proof(key,self.phone,self.now+2000),self.now+2000)
        self.assertEqual(self.dates(key),original)


if __name__=='__main__':unittest.main()
