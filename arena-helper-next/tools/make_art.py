"""Build local art fingerprints from HearthstoneJSON's 256px rendered cards."""
import argparse
import base64
import gzip
import json
from pathlib import Path
from PIL import Image

HEROES = ['warrior','shaman','rogue','paladin','hunter','druid','warlock','mage','priest','demon-hunter','death-knight']
def build(directory, assets):
    cards = json.loads(gzip.decompress((assets/'cards.bin').read_bytes()))['cards']
    entries = {c['cardId']:c['cardId'] for c in cards}
    entries.update({'hero:'+name:'HERO_%02d'%i for i,name in enumerate(HEROES,1)})
    result = {}
    for key, card_id in entries.items():
        with Image.open(directory/(card_id+'.png')) as image:
            assert image.width == 256
            data=image.convert('RGB').crop((78,96,178,160)).resize((10,8),Image.Resampling.BOX).tobytes()
            result[key]=base64.b64encode(data).decode()
    (assets/'art.json').write_text(json.dumps(result,separators=(',',':')))
    print('Visual references:',len(result))
if __name__=='__main__':
    parser=argparse.ArgumentParser()
    parser.add_argument('directory',type=Path)
    parser.add_argument('assets',type=Path)
    args=parser.parse_args()
    build(args.directory,args.assets)
