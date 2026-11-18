import re
import tempfile
import time
import unittest
from pathlib import Path
from cryptography.hazmat.primitives.asymmetric import ec
from server import Store,create_app,Denied,digest
from test_server import proof
from admin_panel import COOKIE


class AdminTests(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory();self.addCleanup(self.tmp.cleanup)
        self.store=Store(Path(self.tmp.name)/'licenses.sqlite')
        self.app=create_app(self.store);self.panel=self.app.extensions['arena_admin']
        self.panel.configure('correct-password-2026');self.client=self.app.test_client()

    def get(self,client=None):
        return (client or self.client).get('/admin',base_url='https://localhost')

    def csrf(self,response):
        return re.search(r'name="csrf" value="([^"]+)"',response.text).group(1)

    def post(self,action,response=None,client=None,**data):
        client=client or self.client
        response=response or self.get(client)
        return client.post('/admin',base_url='https://localhost',data={'csrf':self.csrf(response),'action':action,**data})

    def login(self):
        r=self.post('login',password='correct-password-2026');self.assertEqual(r.status_code,200);return r

    def issue(self,label='ORDER-1'):
        response=self.post('issue',label=label)
        self.assertEqual(response.status_code,200)
        key=re.search(r'AH-[A-F0-9]{48}',response.text).group(0)
        return key,response

    def test_setup_is_closed_and_password_is_hashed(self):
        self.panel.config.unlink()
        self.assertEqual(self.get().status_code,503)
        self.panel.configure('another-long-password')
        self.assertNotIn('another-long-password',self.panel.config.read_text())
        self.assertEqual(self.panel.config.stat().st_mode & 0o777,0o600)

    def test_anonymous_cannot_create_revoke_or_read_keys(self):
        for action in ['issue','revoke','transfer','show']:
            self.assertEqual(self.post(action,label='x').status_code,403)
        with self.store.connect() as db:self.assertEqual(db.execute('SELECT count(*) FROM licenses').fetchone()[0],0)

    def test_csrf_and_double_submit_prevent_duplicate_issue(self):
        self.login();page=self.get()
        self.assertEqual(self.client.post('/admin',base_url='https://localhost',data={'action':'issue','label':'a'}).status_code,403)
        self.assertEqual(self.post('issue',page,label='a').status_code,200)
        self.assertEqual(self.post('issue',page,label='a').status_code,403)
        with self.store.connect() as db:self.assertEqual(db.execute('SELECT count(*) FROM licenses').fetchone()[0],1)

    def test_create_show_copy_and_encrypted_storage(self):
        self.login();key,_=self.issue()
        self.assertNotIn(key,self.get().text)
        self.assertIn(key,self.post('show',id=digest(key.encode())).text)
        with self.store.connect() as db:
            secret=db.execute('SELECT secret FROM admin_keys').fetchone()[0]
            self.assertNotIn(key,secret)
        self.assertNotIn(key.encode(),Path(self.store.path).read_bytes())
        self.assertIn('navigator.clipboard',self.client.get('/admin/ui.js').text)

    def test_old_cli_key_still_activates_and_can_be_revoked(self):
        key=self.store.issue('old');phone=ec.generate_private_key(ec.SECP256R1())
        self.store.activate(proof(key,phone,int(time.time())))
        self.login();self.assertIn('old',self.get().text)
        self.assertEqual(self.post('show',id=digest(key.encode())).status_code,400)
        self.assertEqual(self.post('revoke',id=digest(key.encode())).status_code,200)
        with self.assertRaises(Denied):self.store.activate(proof(key,phone,int(time.time())))

    def test_new_key_rejects_second_phone(self):
        self.login();key,_=self.issue()
        self.store.activate(proof(key,ec.generate_private_key(ec.SECP256R1()),int(time.time())))
        with self.assertRaises(Denied):self.store.activate(proof(key,ec.generate_private_key(ec.SECP256R1()),int(time.time())))

    def test_transfer_waits_then_rotates_once(self):
        self.login();key,_=self.issue();ident=digest(key.encode())
        self.store.activate(proof(key,ec.generate_private_key(ec.SECP256R1()),int(time.time())))
        result=self.post('transfer',id=ident)
        self.assertNotRegex(result.text,r'AH-[A-F0-9]{48}')
        with self.store.connect() as db:
            self.assertEqual(db.execute('SELECT revoked FROM licenses WHERE hash=?',(ident,)).fetchone()[0],1)
            db.execute('UPDATE licenses SET lease_until=? WHERE hash=?',(int(time.time())-61,ident))
        result=self.post('transfer',id=ident)
        new=re.search(r'AH-[A-F0-9]{48}',result.text).group(0);self.assertNotEqual(new,key)
        self.assertEqual(self.post('transfer',id=ident).status_code,400)
        self.store.activate(proof(new,ec.generate_private_key(ec.SECP256R1()),int(time.time())))
        with self.assertRaises(Denied):self.store.activate(proof(key,ec.generate_private_key(ec.SECP256R1()),int(time.time())))

    def test_logout_and_password_reset_invalidate_sessions(self):
        self.login();cookie=self.client.get_cookie(COOKIE).value
        self.post('logout');self.client.set_cookie(COOKIE,cookie)
        self.assertNotIn('Новая лицензия',self.get().text)
        self.login();self.panel.configure('changed-password-2026')
        self.assertNotIn('Новая лицензия',self.get().text)

    def test_password_rate_limit_shared_between_workers(self):
        for _ in range(10):self.assertEqual(self.post('login',password='wrong').status_code,401)
        self.assertEqual(self.post('login',password='correct-password-2026').status_code,429)
        other=create_app(Store(self.store.path)).test_client()
        self.assertEqual(self.post('login',client=other,password='correct-password-2026').status_code,429)

    def test_cookie_headers_xss_and_session_rotation(self):
        initial=self.get();old=self.client.get_cookie(COOKIE).value
        self.login();self.assertNotEqual(old,self.client.get_cookie(COOKIE).value)
        response=self.get();cookie=response.headers['Set-Cookie']
        for flag in ('Secure','HttpOnly','SameSite=Strict','Path=/'):self.assertIn(flag,cookie)
        self.assertEqual(response.headers['Cache-Control'],'no-store')
        self.assertEqual(response.headers['X-Frame-Options'],'DENY')
        self.issue('<script>alert(1)</script>')
        self.assertNotIn('<script>alert',self.get().text)
        self.assertIn('&lt;script&gt;',self.get().text)

    def test_restart_preserves_keys_and_search(self):
        self.login();key,_=self.issue('buyer-needle')
        other=create_app(Store(self.store.path));panel=other.extensions['arena_admin']
        found,_=panel.action('show',digest(key.encode()),'');self.assertEqual(found,key)
        r=self.client.get('/admin?q=buyer-needle',base_url='https://localhost');self.assertIn('buyer-needle',r.text)
        r=self.client.get('/admin?q=not-present',base_url='https://localhost');self.assertNotIn('buyer-needle',r.text)


if __name__=='__main__':unittest.main()
