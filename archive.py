# /// script
# dependencies = ["Pillow"]
# ///
from pathlib import Path
from PIL import Image
from io import BytesIO
import hashlib,json,os,subprocess,time
root=Path.cwd(); base=root/'mod/run/mt-b3'
def git(*args,data=None,env=None): return subprocess.check_output(['git',*args],input=data,env=env)
ref='refs/heads/look-gate/mt-b3'
r=subprocess.run(['git','rev-parse','--verify',ref],capture_output=True,text=True)
parent=r.stdout.strip() if r.returncode==0 else None
env=os.environ.copy(); env['GIT_INDEX_FILE']=str(base/f'archive-index-{time.time_ns()}')
git('read-tree',parent if parent else '--empty',env=env)
files={}
selected=set((base/'selected-frames.txt').read_text().splitlines())
for pattern in ['*.md','analysis*.json','*pixels*.json','*.py','*.sh','*.log','*verification.json','checks.json','selected-frames.txt','lss-api-bytecode.txt','voxy-ingest-bytecode.txt','runtime-jars.json']:
 for p in base.glob(pattern): files[p.name]=p
files['MT-B1-gate.md']=Path('/mnt/data/projects/incoming/mod/run/incoming-sessions/MT-B1-gate.md')
for run in sorted(base.glob('client-*')):
 if not (run/'lod-probe/events.tsv').exists(): continue
 for rel in ['lod-probe/events.tsv','logs/latest.log','power-profile.txt','config/voxy-config.json','config/iris.properties','source-revision.txt','class-sha256.json']:
  p=run/rel
  if p.exists(): files[f'runs/{run.name}/{rel}']=p
 for pattern in ['shaderpacks/*.txt','*-lossless.mkv','source/*']:
  for p in run.glob(pattern): files[f'runs/{run.name}/{p.relative_to(run)}']=p
 for p in (run/'screenshots').glob('*.png'):
  parts=p.stem.split('_')
  if f'{run.name}/{p.name}' in selected: pass
  elif parts[0]=='front':
   if parts[2] not in ['000','045','089']: continue
  elif parts[0]=='replay' and len(parts)>=3 and parts[-2].isdigit():
   if parts[-2] not in ['000','016','045','089']: continue
  files[f'runs/{run.name}/screenshots/{p.name}']=p
for p in Path('mod/build/test-results/test').glob('TEST-*.xml'):
 if any(s in p.name for s in ['Lod','Voxy','RuinInbox','RetainedPlans','RadialChunks','Witnesses','S2CGolden']): files['tests/'+p.name]=p
for folder,prefix in [('stand-evidence','stand-pre-merge'),('stand-merged-evidence','stand')]:
 for p in base.glob(folder+'/**/*'):
  if p.is_file(): files[prefix+'/'+str(p.relative_to(base/folder))]=p
manifest={}
for name,p in files.items():
 data=p.read_bytes()
 if p.suffix=='.png':
  original=Image.open(BytesIO(data)); original.load(); packed=BytesIO(); original.save(packed,format='PNG',optimize=True,compress_level=9)
  data=packed.getvalue(); decoded=Image.open(BytesIO(data))
  assert original.mode==decoded.mode and original.size==decoded.size and original.tobytes()==decoded.tobytes(),name
 assert len(data)<100_000_000,(name,len(data))
 manifest[name]=hashlib.sha256(data).hexdigest()
 blob=git('hash-object','-w','--stdin',data=data).decode().strip()
 git('update-index','--add','--cacheinfo',f'100644,{blob},{name}',env=env)
data=(json.dumps(manifest,indent=2,sort_keys=True)+'\n').encode()
blob=git('hash-object','-w','--stdin',data=data).decode().strip()
git('update-index','--add','--cacheinfo',f'100644,{blob},SHA256.json',env=env)
tree=git('write-tree',env=env).decode().strip()
commit=git('commit-tree',tree,*(['-p',parent] if parent else []),data=b'Archive MT-B3 horizon publication measurements\n\nCo-Authored-By: Codex <noreply@openai.com>\n').decode().strip()
git('update-ref',ref,commit,*([parent] if parent else []))
for name,digest in manifest.items(): assert hashlib.sha256(git('show',commit+':'+name)).hexdigest()==digest,name
print(json.dumps({'commit':commit,'parent':parent,'files_verified':len(manifest)},indent=2))
