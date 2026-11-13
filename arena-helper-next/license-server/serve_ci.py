"""Disposable CI server and three-card instrumentation fixture. Never deployed."""
import json
import tempfile
from pathlib import Path
from database import Database
from server import Store,create_app

base=Path(tempfile.mkdtemp(prefix='arena-ci-'))
store=Store(base/'licenses.sqlite');key=store.issue('disposable-ci')
Path('ci-license.txt').write_text(key)
database=Database(base/'database.json.gz',Path(__file__).parent/'seed');data=database.get().data
ids=['VAC_445','TTN_736','DEEP_016']
rows={c['cardId']:c for c in data['cards']}
fixture=[{'fingerprint':data['art'][i],'name':rows[i]['name']} for i in ids]
p=Path('app/src/androidTest/assets');p.mkdir(parents=True,exist_ok=True)
(p/'server-fixture.json').write_text(json.dumps(fixture,ensure_ascii=False))
create_app(store,database).run(host='127.0.0.1',port=8765)
