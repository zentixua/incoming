from pathlib import Path
import hashlib, json, os, subprocess, time
root=Path.cwd(); base=root/'mod/run/mt-b1'
def git(*args, data=None, env=None):
    return subprocess.check_output(['git',*args], input=data, env=env)
ref='refs/heads/look-gate/mt-b1'
parent=subprocess.run(['git','rev-parse','--verify',ref],capture_output=True,text=True)
parent=parent.stdout.strip() if parent.returncode==0 else None
env=os.environ.copy(); env['GIT_INDEX_FILE']=str(base/f'archive-index-{time.time_ns()}')
git('read-tree',parent if parent else '--empty',env=env)
names=['README.md','GateTileProbeTest.java','gate-jars.json','gate-unit.xml','gate-unit.log',
       'voxy-0.2.16-beta+1.21.11.jar.api.txt','lod-server-support-neoforge-0.20.0+1.21.1.jar.api.txt',
       'build.log','final-build.log','final-build-retry.log','gametest.log','ci.log','checks.json',
       'run-final-checks.sh','run-focused.sh','focused.log','ci-gametest5.log','archive.py']
files={name:base/name for name in names}
files['MT-B1-gate.md']=Path('/mnt/data/projects/incoming/mod/run/incoming-sessions/MT-B1-gate.md')
for name in ['RuinWorkersTest','RuinInboxTest']:
    files['tests/'+name+'.xml']=root/f'mod/build/test-results/test/TEST-ua.zentix.incoming.nuclear.world.{name}.xml'
manifest={}
for name,p in files.items():
    data=p.read_bytes(); manifest[name]=hashlib.sha256(data).hexdigest()
    blob=git('hash-object','-w','--stdin',data=data).decode().strip()
    git('update-index','--add','--cacheinfo',f'100644,{blob},{name}',env=env)
data=(json.dumps(manifest,indent=2,sort_keys=True)+'\n').encode()
blob=git('hash-object','-w','--stdin',data=data).decode().strip()
git('update-index','--add','--cacheinfo',f'100644,{blob},SHA256.json',env=env)
tree=git('write-tree',env=env).decode().strip()
message=b'docs: archive MT-B1 negative LOD gate and lifecycle validation\n\nCo-Authored-By: Codex <noreply@openai.com>\n'
commit=git('commit-tree',tree,*(['-p',parent] if parent else []),data=message).decode().strip()
git('update-ref',ref,commit,*([parent] if parent else []))
for name,sha in manifest.items():
    assert hashlib.sha256(git('show',commit+':'+name)).hexdigest()==sha,name
print(json.dumps({'commit':commit,'parent':parent,'files_verified':len(manifest)},indent=2))
