import json,re,html,pathlib,datetime,gzip
root=pathlib.Path(__file__).resolve().parents[1]
ru=json.loads((root/'tools/ru.json').read_text())
en=json.loads((root/'tools/en.json').read_text())
ru_by_id={x['id']:x for x in ru}; en_by_name={x['name']:x for x in en}
page=(root/'tools/tierlist.html').read_text(); scores={}
for cls,body in re.findall(r'<section class="tab tierlist [^"]+" id="([^"]+)">(.*?)(?=<section class="tab tierlist|\Z)',page,re.S):
 if cls=='any':continue
 for cid,name,value in re.findall(r'<dt[^>]*data-card-image="[^\"]*/([^/\"]+)\.webp"[^>]*>(.*?)</dt><dd class="score[^\"]*">(\d+)</dd>',body,re.S):
  name=html.unescape(re.sub('<.*?>','',name)).strip().split('\n')[0]
  card=ru_by_id.get(cid) or ru_by_id.get(en_by_name.get(name,{}).get('id'))
  if card:scores.setdefault(card['name'],{})[cls]=int(value)
by_name={x['name']:x for x in ru}
cards=[]
for n in sorted({x['name'] for x in ru if x.get('type') in ('MINION','SPELL','WEAPON','LOCATION')}):
 a=by_name[n]
 cards.append({'name':n,'scores':scores.get(n,{}),'cost':a.get('cost'),'cardClass':a.get('cardClass','NEUTRAL').lower().replace('deathknight','death-knight').replace('demonhunter','demon-hunter'),'legendary':a.get('rarity')=='LEGENDARY'})
assert len(scores)>300
obj={'source':'https://www.heartharena.com/tierlist','namesSource':'https://hearthstonejson.com','retrievedAt':datetime.datetime.now(datetime.timezone.utc).isoformat(),'cards':cards}
(root/'app/src/main/assets/cards.bin').write_bytes(gzip.compress(json.dumps(obj,ensure_ascii=False,separators=(',',':')).encode(),mtime=0))
print('Names:',len(cards),'rated:',len(scores),'class scores:',sum(map(len,scores.values())))
