import base64
import concurrent.futures
import json
import secrets
import tempfile
import unittest
from pathlib import Path
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec
from server import Store, Denied, digest, create_app


def proof(key, private, now=1000, nonce=None):
    public = private.public_key().public_bytes(serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo)
    nonce = nonce or secrets.token_hex(16)
    msg = f'arena-license-v1\n{digest(key.encode())}\n{digest(public)}\n{now}\n{nonce}'.encode()
    return dict(license=key, publicKey=base64.b64encode(public).decode(), timestamp=now, nonce=nonce,
                signature=base64.b64encode(private.sign(msg,ec.ECDSA(hashes.SHA256()))).decode())


class LicenseTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.store = Store(Path(self.temp.name)/'licenses.sqlite')
        self.key = self.store.issue('Test buyer')
        self.phone = ec.generate_private_key(ec.SECP256R1())
        self.other = ec.generate_private_key(ec.SECP256R1())

    def tearDown(self):
        self.temp.cleanup()

    def test_same_phone_refreshes(self):
        a=self.store.activate(proof(self.key,self.phone),1000)
        b=self.store.activate(proof(self.key,self.phone,1100),1100)
        self.assertEqual(a['device'],b['device'])
        self.assertEqual(b['leaseSeconds'],900)

    def test_copied_key_rejected_on_other_phone(self):
        self.store.activate(proof(self.key,self.phone),1000)
        with self.assertRaises(Denied) as error:
            self.store.activate(proof(self.key,self.other),1000)
        self.assertEqual(error.exception.code,'OTHER_DEVICE')

    def test_simultaneous_first_activation_has_exactly_one_winner(self):
        def activate(phone):
            try: return bool(self.store.activate(proof(self.key,phone),1000))
            except Denied: return False
        with concurrent.futures.ThreadPoolExecutor(2) as pool:
            self.assertEqual(sum(pool.map(activate,[self.phone,self.other])),1)

    def test_replay_rejected(self):
        request=proof(self.key,self.phone)
        self.store.activate(request,1000)
        with self.assertRaises(Denied): self.store.activate(request,1000)

    def test_forged_signature_does_not_bind(self):
        request=proof(self.key,self.phone)
        request['signature']=proof(self.key,self.other)['signature']
        with self.assertRaises(Denied): self.store.activate(request,1000)
        self.store.activate(proof(self.key,self.other),1000)

    def test_unknown_and_revoked_license_denied(self):
        with self.assertRaises(Denied): self.store.activate(proof('AH-'+'A'*48,self.phone),1000)
        self.store.revoke(self.key)
        with self.assertRaises(Denied): self.store.activate(proof(self.key,self.phone),1000)

    def test_transfer_waits_until_previous_lease_expires(self):
        self.store.activate(proof(self.key,self.phone),1000)
        with self.assertRaises(ValueError): self.store.reset(self.key,2000)
        self.store.revoke(self.key)
        with self.assertRaises(ValueError): self.store.reset(self.key,1959)
        self.store.reset(self.key,1960)
        self.store.activate(proof(self.key,self.other,1960),1960)
        with self.assertRaises(Denied): self.store.activate(proof(self.key,self.phone,1960),1960)

    def test_clock_and_malformed_requests(self):
        for body in ({},None,{'timestamp':'oops'},proof(self.key,self.phone,10)):
            with self.assertRaises(Denied): self.store.activate(body,1000)

    def test_database_persists_binding_without_plaintext_key(self):
        self.store.activate(proof(self.key,self.phone),1000)
        restored=Store(self.store.path)
        with self.assertRaises(Denied): restored.activate(proof(self.key,self.other),1000)
        self.assertNotIn(self.key.encode(),Path(self.store.path).read_bytes())

    def test_http_contract_and_body_limit(self):
        client=create_app(self.store).test_client()
        self.assertEqual(client.get('/health').status_code,200)
        self.assertEqual(client.post('/v1/activate',json={}).status_code,400)
        self.assertEqual(client.post('/v1/activate',data='x'*40000,content_type='application/json').status_code,413)


if __name__=='__main__': unittest.main()
