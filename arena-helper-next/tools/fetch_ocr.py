import hashlib,pathlib,urllib.request
path=pathlib.Path(__file__).resolve().parents[1]/'app/src/main/assets/rus.traineddata'
expected='e16e5e036cce1d9ec2b00063cf8b54472625b9e14d893a169e2b0dedeb4df225'
if not path.exists() or hashlib.sha256(path.read_bytes()).hexdigest()!=expected:
 data=urllib.request.urlopen('https://raw.githubusercontent.com/tesseract-ocr/tessdata_fast/4.1.0/rus.traineddata',timeout=120).read()
 assert hashlib.sha256(data).hexdigest()==expected,'OCR model checksum mismatch'
 path.parent.mkdir(parents=True,exist_ok=True)
 path.write_bytes(data)
print('Russian OCR model SHA-256 verified:',expected)
