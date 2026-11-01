import gzip,json,struct,sys,zipfile
with zipfile.ZipFile(sys.argv[1]) as z:
 assert len(z.read('assets/rus.traineddata'))>1000000
 cards=json.loads(gzip.decompress(z.read('assets/cards.json.gz')))['cards']
 assert len(cards)>5000 and sum(bool(c['scores']) for c in cards)>300
 libs=[n for n in z.namelist() if n.startswith('lib/') and n.endswith('.so')]
 assert libs and all(n.startswith('lib/arm64-v8a/') for n in libs)
 for n in libs:
  b=z.read(n); assert b[:4]==b'\x7fELF' and b[4]==2 and b[5]==1
  off=struct.unpack_from('<Q',b,32)[0]; size,count=struct.unpack_from('<HH',b,54)
  loads=[struct.unpack_from('<Q',b,off+i*size+48)[0] for i in range(count) if struct.unpack_from('<I',b,off+i*size)[0]==1]
  assert loads and min(loads)>=16384,(n,loads)
  print(n,'16 KB ELF alignment OK')
print('Offline Russian model, catalog and ARM64 libraries verified.')
