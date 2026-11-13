"""Run on the server only. Validated, atomic rotation updates; old snapshot survives failures."""
import argparse
import base64
import copy
import datetime
import gzip
import io
import json
import logging
import re
import time
import urllib.request
from pathlib import Path
from bs4 import BeautifulSoup
from PIL import Image
import numpy as np
from database import Database, Snapshot

KEYS=['death-knight','druid','hunter','mage','paladin','priest','rogue','shaman','warlock','warrior','demon-hunter']
LABELS=['Рыцарь смерти','Друид','Охотник','Маг','Паладин','Жрец','Разбойник','Шаман','Чернокнижник','Воин','Охотник на демонов']
STATS='https://hsreplay.net/api/v1/arena/classes_stats/?game_type=BGT_ARENA'
GROUPS='https://hsreplay.net/api/v1/arena/card_packages/free/?game_type=BGT_ARENA'


def fetch(url,limit=32*1024*1024):
    if not url.startswith('https://'): raise ValueError('HTTPS required')
    request=urllib.request.Request(url,headers={'User-Agent':'ArenaHelperServer/1.4','Accept-Encoding':'identity'})
    with urllib.request.urlopen(request,timeout=45) as response:
        if not response.url.startswith('https://'): raise ValueError('Unsafe redirect')
        raw=response.read(limit+1)
    if len(raw)>limit: raise ValueError('Response too large')
    return raw


def regular(data):
    if 'ArenaGameTypeFilter.BGT_ARENA' not in data['selected_params']: raise ValueError('Wrong arena mode')
    return data


def parse_scores(html):
    soup=BeautifulSoup(html,'html.parser'); result={}
    for section in soup.select('section.tierlist'):
        cls=section.get('id')
        if cls not in KEYS: continue
        for dl in section.select('dl'):
            dt=dl.find('dt');dd=dl.find('dd')
            if not dt or not dd: continue
            image=dt.get('data-card-image','');match=re.search(r'/([A-Za-z0-9_]+)\.webp',image)
            score=re.search(r'\d{1,3}',dd.get_text())
            if match and score:
                value=int(score[0])
                if not 0<=value<=200: raise ValueError('Invalid score')
                result.setdefault(match[1],{})[cls]=value
    if len(result)<800 or sum(map(len,result.values()))<3000: raise ValueError('Incomplete tier list')
    return result


def fingerprint(raw):
    with Image.open(io.BytesIO(raw)) as image:
        if image.width!=256 or image.height<192: raise ValueError('Wrong reference dimensions')
        pixels=np.asarray(image.convert('RGB').crop((78,96,178,160)),dtype=np.int32)
    cells=[]
    for y in range(8):
        for x in range(10): cells.extend((pixels[y*8:(y+1)*8,x*10:(x+1)*10].sum(axis=(0,1))//80).tolist())
    return base64.b64encode(bytes(cells)).decode()


def refresh(database):
    previous=database.get().data;result=copy.deepcopy(previous)
    ratings=parse_scores(fetch('https://www.heartharena.com/en/tierlist',4*1024*1024))
    index=fetch('https://api.hearthstonejson.com/v1/latest/',512*1024).decode()
    build=re.search(r'href="/v1/(\d+)"',index)
    if not build: raise ValueError('Missing metadata version')
    source=f'https://api.hearthstonejson.com/v1/{build[1]}/ruRU/cards.json'
    metadata={c['id']:c for c in json.loads(fetch(source))}
    warnings=[];ids=set(ratings)
    packages=[]
    try:
        groups=regular(json.loads(fetch(GROUPS)))['data']['ALL']
        if len(groups)<50: raise ValueError('Incomplete packages')
        for group in groups:
            key=group['package_key_card_id'];extras=group['package_card_ids'];ids.add(key);ids.update(extras)
            packages.append({'key':metadata[key]['name'],'cards':[{'name':metadata[i]['name'],'cost':metadata[i].get('cost',-1)} for i in extras]})
    except Exception:
        packages=previous.get('packages',[]);warnings.append('Состав групп: предыдущая версия')
        ids.update(c['cardId'] for c in previous['cards'])
    # Preserve separately curated event cards and their recognition even if no source rating exists.
    ids.update(c['cardId'] for c in previous['cards'] if not c['scores'] or '_EVENT_' in c['cardId'])
    cards=[];old={c['cardId']:c for c in previous['cards']}
    for card_id in sorted(ids):
        m=metadata.get(card_id)
        if m is None: raise ValueError('Missing card metadata: '+card_id)
        cards.append({'cardId':card_id,'name':m['name'],'cost':m.get('cost',-1),'rarity':m.get('rarity',''),'scores':ratings.get(card_id,{})})
        if card_id not in result['art']:
            if not re.fullmatch(r'[A-Za-z0-9_]+',card_id): raise ValueError('Invalid ID')
            result['art'][card_id]=fingerprint(fetch(f'https://art.hearthstonejson.com/v1/render/latest/enUS/256x/{card_id}.png',1024*1024))
    stats={s['id']:{**s,'cached':True} for s in previous['classStats']}
    try:
        rows=regular(json.loads(fetch(STATS)))['data'];incoming={}
        for row in rows:
            ident=row['deck_class'];pos=10 if ident==14 else ident-1
            if pos not in range(11): continue
            rate=row['win_rate']
            if KEYS[pos] in incoming or not 0<=rate<=100: raise ValueError('Invalid hero statistics')
            incoming[KEYS[pos]]={'id':KEYS[pos],'label':LABELS[pos],'winRate':rate,'drafts':row.get('num_drafts',0),'cached':False}
        if len(incoming)<8: raise ValueError('Incomplete hero statistics')
        stats.update(incoming)
    except Exception: warnings.append('Статистика героев: предыдущая версия')
    now=datetime.datetime.now(datetime.timezone.utc).isoformat()
    result.update(cards=cards,packages=packages,classStats=list(stats.values()),retrievedAt=now,revision=now,
                  metadataBuild=build[1],namesSource=source,updateWarnings='; '.join(warnings),arenaMode='REGULAR')
    database.publish(result)
    return len(cards)


if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('--loop',action='store_true');parser.add_argument('--path',default='/data/database.json.gz');args=parser.parse_args()
    logging.basicConfig(level=logging.INFO)
    db=Database(args.path,Path(__file__).parent/'seed')
    while True:
        try: logging.info('Published validated database: %s cards',refresh(db))
        except Exception as error:
            logging.error('Database update rejected; previous snapshot retained: %s',error)
            if not args.loop: raise SystemExit(1)
        if not args.loop: break
        time.sleep(6*60*60)
