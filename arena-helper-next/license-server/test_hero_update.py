import base64
import copy
import io
import unittest
from pathlib import Path
from PIL import Image
from database import seed_snapshot,Snapshot
from update_database import refresh_portraits

class HeroUpdateTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.seed=seed_snapshot(Path(__file__).parent/'seed')
        out=io.BytesIO();image=Image.new('RGB',(256,384));image.putdata([(x%256,y%256,(x+y)%256) for y in range(384) for x in range(256)]);image.save(out,format='PNG');cls.png=out.getvalue()

    def test_full_skin_ids_and_all_classes_use_metadata_not_numeric_prefix(self):
        data=copy.deepcopy(self.seed);calls=[]
        rows={}
        for i,cls in enumerate(['DEATHKNIGHT','DEMONHUNTER','MAGE']):
            ident=f'HERO_99_test_{i}_gold';rows[ident]={'id':ident,'type':'HERO','cardClass':cls}
        rows['bad']={'id':'HERO_99/../../bad','type':'HERO','cardClass':'MAGE'}
        rows['power']={'id':'HERO_99_power','type':'HERO_POWER','cardClass':'MAGE'}
        def loader(url,limit):calls.append(url);return self.png
        self.assertEqual(refresh_portraits(data,rows,loader),3)
        self.assertEqual(len(calls),3)
        self.assertIn('death-knight:HERO_99_test_0_gold',data['portraits'])
        self.assertIn('demon-hunter:HERO_99_test_1_gold',data['portraits'])
        self.assertEqual(refresh_portraits(data,rows,loader),0)
        self.assertEqual(len(calls),3)

    def test_failed_download_keeps_previous_portraits_and_is_retried(self):
        data=copy.deepcopy(self.seed);before=dict(data['portraits'])
        rows={'a':{'id':'HERO_new_skin','type':'HERO','cardClass':'MAGE'}}
        self.assertEqual(refresh_portraits(data,rows,lambda *_:b'not an image'),0)
        self.assertEqual(data['portraits'],before);self.assertEqual(data['portraitPending'],1)
        self.assertEqual(refresh_portraits(data,rows,lambda *_:self.png),1)
        self.assertEqual(data['portraitPending'],0)

    def test_one_known_hero_is_not_discarded_with_two_unknown_skins(self):
        snapshot=Snapshot(self.seed);blank=base64.b64encode(bytes(240)).decode()
        known=self.seed['portraits']['warrior:HERO_01']
        payload={'cards':[{'queries':[blank],'gold':False} for _ in range(3)],'heroes':[[blank],[known],[blank]]}
        result=snapshot.analyze(payload)
        self.assertEqual([h['id'] if h else None for h in result['heroes']],[None,'warrior',None])

    def test_all_unknown_does_not_invent_a_hero(self):
        snapshot=Snapshot(self.seed);blank=base64.b64encode(bytes(240)).decode()
        payload={'cards':[{'queries':[blank],'gold':False} for _ in range(3)],'heroes':[[blank] for _ in range(3)]}
        self.assertEqual(snapshot.analyze(payload)['heroes'],[None,None,None])
