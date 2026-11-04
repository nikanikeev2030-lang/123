import base64,gzip,json,sys,zipfile
with zipfile.ZipFile(sys.argv[1]) as apk:
    entries=apk.namelist()
    assert not any('tesseract' in n.lower() or n.endswith('.traineddata') for n in entries)
    assert not any(n.startswith('lib/') and n.endswith('.so') for n in entries)
    cards=json.loads(gzip.decompress(apk.read('assets/cards.bin')))['cards']
    refs=json.loads(apk.read('assets/art.json'))
    assert len(cards)>=800 and all(c['cardId'] in refs for c in cards)
    assert len([k for k in refs if k.startswith('hero:')])==11
    assert all(len(base64.b64decode(v,validate=True))==240 for v in refs.values())
    print(f'{len(cards)} cards, 11 hero fingerprints, no OCR model or native libraries.')
