import sys,zipfile
with zipfile.ZipFile(sys.argv[1]) as apk:
    names=apk.namelist()
    assert not any(n.startswith('assets/') for n in names), 'Private data bundled in APK'
    assert not any(n.endswith('.traineddata') or 'tesseract' in n.lower() for n in names)
    dex=b''.join(apk.read(n) for n in names if n.endswith('.dex'))
    for host in (b'www.heartharena.com',b'api.hearthstonejson.com',b'art.hearthstonejson.com',b'hsreplay.net'):
        assert host not in dex, 'Upstream downloader remains in client'
    print('No databases, reference fingerprints or upstream download URLs in APK')
