from pathlib import Path
import gzip, hashlib, json
root=Path('/mnt/data/projects/incoming/mod/run/wt-MT-OPT/mod/run/mt-s2/corpus')
p=root/'manifest.json'; m=json.loads(p.read_text()); roots={tuple(r['chunk']) for r in m['roots']}
assert len(roots)==190 and len(m['chunks'])==2177
size=0
for row in m['chunks']:
 raw=gzip.decompress((root/row['file']).read_bytes()); size+=len(raw)
 assert len(raw)==row['bytes'] and hashlib.sha256(raw).hexdigest()==row['raw_sha256'],row['file']
jar=Path('/mnt/data/projects/incoming/mod/run/wt-MT-OPT/mod/run/mt-s2/runtime-hold/createpropulsion-1.1.5.jar')
result={'manifest_sha256':hashlib.sha256(p.read_bytes()).hexdigest(),'roots':len(roots),'cases':len(roots)*4,'chunks':len(m['chunks']),'raw_bytes':size,'seed':m['seed'],'minY':m['minY'],'height':m['height'],'dataVersion':m['dataVersion'],'provider_sha256':hashlib.sha256(jar.read_bytes()).hexdigest(),'provider_bytes':jar.stat().st_size}
Path('run/mt-check-corpus/provenance.json').write_text(json.dumps(result,indent=2)+'\n'); print(json.dumps(result))
