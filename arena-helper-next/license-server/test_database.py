import base64
import copy
import hashlib
import json
import secrets
import tempfile
import time
import unittest
from pathlib import Path
from unittest.mock import patch
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec
from database import Database, Snapshot, seed_snapshot
from server import Store, create_app, digest
from test_server import proof
from update_database import parse_scores, refresh

SEED=Path(__file__).parent/'seed'


def data_proof(key,private,action,payload):
    data=proof(key,private,int(time.time()))
    raw=json.dumps(payload,separators=(',',':'))
    device=digest(base64.b64decode(data['publicKey']))
    msg=f'arena-license-v2\n{action}\n{digest(raw.encode())}\n{digest(key.encode())}\n{device}\n{data["timestamp"]}\n{data["nonce"]}'.encode()
    data['payload']=raw
    data['signature']=base64.b64encode(private.sign(msg,ec.ECDSA(hashes.SHA256()))).decode()
    return data


class DataTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.seed=seed_snapshot(SEED)
        cls.snapshot=Snapshot(cls.seed)

    def test_all_normal_reprints_match_without_alias_ambiguity(self):
        for card_id, card in self.snapshot.cards.items():
            found=self.snapshot.closest([self.seed["art"][card_id]],self.snapshot.card_index)
            self.assertIsNotNone(found,card_id)
            self.assertEqual(card["name"],self.snapshot.cards[found["id"]]["name"])

    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup)
        self.db=Database(Path(self.temp.name)/'database.json.gz',SEED)
        self.store=Store(Path(self.temp.name)/'licenses.sqlite')
        self.key=self.store.issue('test');self.phone=ec.generate_private_key(ec.SECP256R1());self.other=ec.generate_private_key(ec.SECP256R1())
        self.client=create_app(self.store,self.db).test_client()

    def activate(self):
        self.store.activate(proof(self.key,self.phone,int(time.time())))

    def payload(self,ids=('VAC_445','TTN_736','DEEP_016')):
        blank=base64.b64encode(bytes(240)).decode()
        return {'cards':[{'queries':[self.seed['art'][i]],'gold':False} for i in ids],'heroes':[[blank] for _ in range(3)]}

    def send(self,action,payload,phone=None):
        return self.client.post('/v1/'+action,json=data_proof(self.key,phone or self.phone,action,payload))

    def test_requires_activation_and_original_device(self):
        self.assertEqual(self.send('info',{}).status_code,403)
        self.activate()
        self.assertEqual(self.send('info',{},self.other).status_code,403)
        self.assertEqual(self.send('info',{}).status_code,200)

    def test_only_current_three_results_are_returned(self):
        self.activate();response=self.send('analyze',self.payload())
        self.assertEqual(response.status_code,200)
        data=response.get_json();self.assertEqual(len(data['cards']),3)
        self.assertEqual([m['id'] for m in data['matches']],['VAC_445','TTN_736','DEEP_016'])
        self.assertFalse({'art','portraits','variants'} & set(data))
        self.assertEqual(response.headers['Cache-Control'],'no-store')
        self.assertNotIn('cards',self.send('info',{}).get_json())

    def test_tampered_payload_and_cross_endpoint_rejected(self):
        self.activate();body=data_proof(self.key,self.phone,'analyze',self.payload())
        body['payload']='{}'
        self.assertEqual(self.client.post('/v1/analyze',json=body).status_code,400)
        body=data_proof(self.key,self.phone,'info',{})
        self.assertEqual(self.client.post('/v1/analyze',json=body).status_code,400)

    def test_revoke_blocks_data_immediately(self):
        self.activate();self.assertEqual(self.send('info',{}).status_code,200)
        self.store.revoke(self.key);self.assertEqual(self.send('analyze',self.payload()).status_code,403)

    def test_signed_replay_and_bulk_request_rejected(self):
        self.activate();body=data_proof(self.key,self.phone,'info',{})
        self.assertEqual(self.client.post('/v1/info',json=body).status_code,200)
        self.assertEqual(self.client.post('/v1/info',json=body).status_code,409)
        data=self.payload();data['cards']*=2
        self.assertEqual(self.send('analyze',data).status_code,400)

    def test_rate_limit_applies_to_valid_device(self):
        self.activate()
        now=int(time.time())
        with self.store.connect() as db:
            pub=self.phone.public_key().public_bytes(serialization.Encoding.DER,serialization.PublicFormat.SubjectPublicKeyInfo)
            for _ in range(120): db.execute('INSERT INTO nonces VALUES(?,?,?)',(digest(pub),secrets.token_hex(16),now+600))
        self.assertEqual(self.send('info',{}).status_code,429)

    def test_all_special_treatments_keep_original_cards(self):
        count=0
        for ident,versions in self.seed['variants'].items():
            for value in versions.values():
                found=self.snapshot.closest([value],self.snapshot.card_index)
                self.assertIsNotNone(found,ident)
                self.assertEqual(self.snapshot.cards[found['id']]['name'],self.snapshot.cards[ident]['name']);count+=1
        self.assertEqual(count,205)

    def test_hero_slots_and_blank_middle(self):
        blank=base64.b64encode(bytes(240)).decode()
        classes=list(self.snapshot.stats)
        for first,last in zip(classes,classes[1:]+classes[:1]):
            refs={cls:next(v for k,v in self.seed['portraits'].items() if k.startswith(cls+':')) for cls in (first,last)}
            payload={'cards':[{'queries':[blank],'gold':False} for _ in range(3)],'heroes':[[refs[first]],[blank],[refs[last]]]}
            result=self.snapshot.analyze(payload)
            self.assertEqual([h['id'] if h else None for h in result['heroes']],[first,None,last])

    def test_failed_update_does_not_replace_database(self):
        before=self.db.path.read_bytes()
        with patch('update_database.fetch',return_value=b'<html>maintenance</html>'):
            with self.assertRaises(ValueError): refresh(self.db)
        self.assertEqual(self.db.path.read_bytes(),before)
        bad=copy.deepcopy(self.seed);bad['cards']=bad['cards'][:10]
        with self.assertRaises(ValueError):self.db.publish(bad)
        self.assertEqual(self.db.path.read_bytes(),before)

    def test_valid_snapshot_reloads_without_restart(self):
        changed=copy.deepcopy(self.seed);changed['retrievedAt']='2099-01-01T00:00:00Z'
        self.db.publish(changed)
        self.assertEqual(self.db.get().info()['retrievedAt'],'2099-01-01T00:00:00Z')

    def test_no_public_catalog_route(self):
        for path in ('/seed/cards.bin','/database.json.gz','/v1/catalog','/static/seed/cards.bin'):
            self.assertEqual(self.client.get(path).status_code,404)


if __name__=='__main__':unittest.main()
