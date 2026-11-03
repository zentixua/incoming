# /// script
# dependencies = ["Pillow"]
# ///
from pathlib import Path
import hashlib,json,os,subprocess,time
from io import BytesIO
from PIL import Image
root=Path.cwd(); base=root/'mod/run/mt-lod-buffer'
def git(*args,data=None,env=None):
    return subprocess.check_output(['git',*args],input=data,env=env)
ref='refs/heads/look-gate/mt-lod-buffer'
r=subprocess.run(['git','rev-parse','--verify',ref],capture_output=True,text=True)
parent=r.stdout.strip() if r.returncode==0 else None
env=os.environ.copy(); env['GIT_INDEX_FILE']=str(base/f'archive-index-{time.time_ns()}')
git('read-tree',parent if parent else '--empty',env=env)
files={}
for name in ['README.md','analysis.json','analysis-first.json','analysis-clear.json','analysis-sections.json','replay-pixels-clear.json','replay-pixels-sections.json','replay_pixels.py','runtime-jobs-sections.txt','pack_replay.py','replay-video-verification.json','analyse.py','archive.py','build.log','gametest.log','checks.sh','ci.log','ci-final.log','ci-sections.log','checks.json','prepare-client.sh','run-client.sh','client-console.log','client-console-first.log','client-console-clear.log']:
    p=base/name
    if p.exists(): files[name]=p
files['MT-B1-gate.md']=Path('/mnt/data/projects/incoming/mod/run/incoming-sessions/MT-B1-gate.md')
files['gate-jars.json']=root/'mod/run/mt-b1/gate-jars.json'
for p in sorted(base.glob('client-*')):
    if not p.is_dir(): continue
    for rel in ['lod-probe/events.tsv','logs/latest.log','power-profile.txt','config/voxy-config.json','config/iris.properties','replay-lossless.mkv']:
        q=p/rel
        if rel=='replay-lossless.mkv' and p.name=='client-20261103-234620': continue
        if q.exists(): files[f'runs/{p.name}/{rel}']=q
    for q in p.glob('shaderpacks/*.txt'): files[f'runs/{p.name}/shaderpacks/{q.name}']=q
    for q in p.glob('screenshots/*.png'):
        if q.name.startswith('replay_') and q.name[7:10].isdigit(): continue
        if p.name=='client-20261103-234620' and q.name not in ['panorama_1000_0_complete.png','panorama_1000_1_complete.png','replay_stale_unrepaired.png','replay_repaired_1.png']: continue
        if p.name=='client-20261104-003305' and not (q.name.startswith('panorama_') or q.name.startswith('replay_') or '_4_tick' in q.name): continue
        files[f'runs/{p.name}/screenshots/{q.name}']=q
for q in (root/'mod/src/devtest/java/ua/zentix/incoming/client').glob('Lod*Probe*.java'):
    files['probe-source/'+q.name]=q
q=root/'mod/src/devtest/java/ua/zentix/incoming/client/LodBufferScenario.java'
if q.exists(): files['probe-source/'+q.name]=q
q=root/'mod/build/test-results/test/TEST-ua.zentix.incoming.scenario.lod.LodBufferTest.xml'
if q.exists(): files['tests/'+q.name]=q
manifest={}
for name,p in files.items():
    data=p.read_bytes()
    if p.suffix=='.png':
        original=Image.open(BytesIO(data)); original.load()
        packed=BytesIO(); original.save(packed,format='PNG',optimize=True,compress_level=9)
        data=packed.getvalue(); decoded=Image.open(BytesIO(data))
        assert original.mode==decoded.mode and original.size==decoded.size and original.tobytes()==decoded.tobytes(),name
    manifest[name]=hashlib.sha256(data).hexdigest()
    blob=git('hash-object','-w','--stdin',data=data).decode().strip()
    git('update-index','--add','--cacheinfo',f'100644,{blob},{name}',env=env)
data=(json.dumps(manifest,indent=2,sort_keys=True)+'\n').encode()
blob=git('hash-object','-w','--stdin',data=data).decode().strip()
git('update-index','--add','--cacheinfo',f'100644,{blob},SHA256.json',env=env)
tree=git('write-tree',env=env).decode().strip()
message=b'docs: archive client LOD buffer gate measurements\n\nCo-Authored-By: Codex <noreply@openai.com>\n'
commit=git('commit-tree',tree,*(['-p',parent] if parent else []),data=message).decode().strip()
git('update-ref',ref,commit,*([parent] if parent else []))
for name,sha in manifest.items(): assert hashlib.sha256(git('show',commit+':'+name)).hexdigest()==sha,name
print(json.dumps({'commit':commit,'parent':parent,'files_verified':len(manifest)},indent=2))
