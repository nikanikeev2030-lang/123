"""Private immutable recognition snapshots. Only three match results leave the server."""
import base64
import gzip
import json
import os
import threading
from pathlib import Path
import numpy as np


def decode(value):
    raw=base64.b64decode(value,validate=True)
    if len(raw)!=240: raise ValueError('Invalid fingerprint')
    return np.frombuffer(raw,dtype=np.uint8).astype(np.int16)


def seed_snapshot(path):
    path=Path(path)
    catalog=json.loads(gzip.decompress((path/'cards.bin').read_bytes()))
    art=json.loads((path/'art.json').read_text())
    extra=json.loads((path/'extra.json').read_text())
    known={c['cardId'] for c in catalog['cards']}
    catalog['cards'] += [c for c in extra['cards'] if c['cardId'] not in known]
    art.update(extra['art'])
    catalog.update(art=art,variants=json.loads((path/'variants.json').read_text()),
                   portraits=json.loads(gzip.decompress((path/'portraits.bin').read_bytes())))
    return catalog


class Snapshot:
    def __init__(self, data):
        self.data=data
        if data.get('arenaMode','REGULAR')!='REGULAR': raise ValueError('Wrong arena mode')
        if len(data['cards'])<800: raise ValueError('Incomplete catalog')
        self.cards={c['cardId']:c for c in data['cards']}
        self.packages={c['key']:c for c in data.get('packages',[])}
        self.stats={s['id']:s for s in data['classStats']}
        if len(self.stats)!=11: raise ValueError('Missing hero statistics')
        if any(not 0<=s['winRate']<=100 for s in self.stats.values()): raise ValueError('Invalid hero statistics')
        if sum(bool(c['scores']) for c in self.cards.values())<800: raise ValueError('Incomplete ratings')
        card_refs=[]
        for card_id,card in self.cards.items():
            if not card['name'] or card.get('cost',-1)<-1: raise ValueError('Invalid card')
            if any(type(s)!=int or not 0<=s<=200 for s in card['scores'].values()): raise ValueError('Invalid rating')
            card_refs.append((card_id,decode(data['art'][card_id])))
            card_refs += [(card_id,decode(v)) for v in data.get('variants',{}).get(card_id,{}).values()]
            special=data['art'].get('signature:'+card_id)
            if special: card_refs.append((card_id,decode(special)))
        hero_refs=[(key.split(':')[0],decode(value)) for key,value in data['portraits'].items()]
        if len(hero_refs)<700 or {k for k,_ in hero_refs}!=set(self.stats): raise ValueError('Incomplete portraits')
        self.card_index=self.index(card_refs)
        self.hero_index=self.index(hero_refs)

    @staticmethod
    def index(refs):
        names=np.array([k for k,_ in refs])
        matrix=np.stack([v for _,v in refs])
        means=matrix.reshape(-1,80,3).mean(axis=1).astype(np.int16)
        return names,matrix,means

    @staticmethod
    def closest(encoded,index,animated=False):
        if type(encoded)!=list or not 1<=len(encoded)<=9: raise ValueError('Invalid candidate count')
        names,matrix,means=index
        distances=np.full(len(names),2**30,dtype=np.int64)
        for value in encoded:
            q=decode(value)
            levels=q.reshape(80,3).sum(axis=1)//3
            if levels.max()-levels.min()<18: continue
            delta=q-matrix
            if animated:
                shift=np.clip(q.reshape(80,3).mean(axis=0).astype(np.int16)-means,-32,32)
                delta=delta.reshape(-1,80,3)-shift[:,None,:]
                errors=np.minimum(65,np.abs(delta)).sum(axis=(1,2))
            else: errors=np.abs(delta).sum(axis=1)
            distances=np.minimum(distances,errors)
        best=int(distances.argmin()); error=float(distances[best])/240
        if error>(27 if animated else 26): return None
        name=str(names[best]);others=distances[names!=name]
        if len(others) and float(others.min())/240-error<(5 if animated else 4): return None
        return {'id':name,'confidence':1-error/160}

    def info(self):
        return {'retrievedAt':self.data['retrievedAt'],'cardCount':len(self.cards),
                'classStats':list(self.stats.values()),'revision':self.data.get('revision',self.data['retrievedAt'])}

    def analyze(self,payload):
        slots=payload.get('cards'); heroes=payload.get('heroes')
        if type(slots)!=list or len(slots)!=3 or type(heroes)!=list or len(heroes)!=3: raise ValueError('Exactly three slots required')
        matches=[];details=[];packages=[]
        for slot in slots:
            if set(slot)!={'queries','gold'} or type(slot['gold'])!=bool or len(slot['queries'])>3: raise ValueError('Invalid card slot')
            match=self.closest(slot['queries'],self.card_index)
            if not match and slot['gold']: match=self.closest(slot['queries'],self.card_index,True)
            matches.append(match)
            if match:
                card=self.cards[match['id']];details.append(card)
                package=self.packages.get(card['name'])
                if package: packages.append(package)
        hero_matches=[None]*3
        if sum(m is not None for m in matches)<2:
            hero_matches=[self.closest(queries,self.hero_index) for queries in heroes]
            if sum(h is not None for h in hero_matches)<2: hero_matches=[None]*3
        return {'matches':matches,'heroes':hero_matches,'cards':details,'packages':packages,**self.info()}


class Database:
    def __init__(self,path,seed=None):
        self.path=Path(path);self.lock=threading.Lock();self.stamp=None;self.current=None
        if not self.path.exists() and seed:
            self.publish(seed_snapshot(seed))

    def get(self):
        with self.lock:
            stamp=self.path.stat().st_mtime_ns
            if stamp!=self.stamp:
                candidate=Snapshot(json.loads(gzip.decompress(self.path.read_bytes())))
                self.current=candidate;self.stamp=stamp
            return self.current

    def publish(self,data):
        Snapshot(data) # Reject bad updates before replacing the live snapshot.
        self.path.parent.mkdir(parents=True,exist_ok=True)
        raw=gzip.compress(json.dumps(data,ensure_ascii=False,separators=(',',':')).encode())
        import tempfile
        with tempfile.NamedTemporaryFile(dir=self.path.parent,delete=False) as out:
            tmp=out.name;out.write(raw);out.flush();os.fsync(out.fileno())
        try: os.replace(tmp,self.path)
        finally:
            if os.path.exists(tmp): os.unlink(tmp)
