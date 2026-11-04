from pathlib import Path
import hashlib,json,os,subprocess,time
base=Path('mod/run/mt-b3')
def git(*args,data=None,env=None): return subprocess.check_output(['git',*args],input=data,env=env)
ref='refs/heads/look-gate/mt-b3';parent=git('rev-parse',ref).decode().strip()
env={**os.environ,'GIT_INDEX_FILE':str((base/f'archive-index-update-{time.time_ns()}').resolve())}
git('read-tree',parent,env=env)
manifest=json.loads(git('show',parent+':SHA256.json'))
for name in ['README.md','archive-update.py']:
 data=(base/name).read_bytes();manifest[name]=hashlib.sha256(data).hexdigest()
 blob=git('hash-object','-w','--stdin',data=data).decode().strip()
 git('update-index','--add','--cacheinfo',f'100644,{blob},{name}',env=env)
data=(json.dumps(manifest,indent=2,sort_keys=True)+'\n').encode()
blob=git('hash-object','-w','--stdin',data=data).decode().strip()
git('update-index','--add','--cacheinfo',f'100644,{blob},SHA256.json',env=env)
tree=git('write-tree',env=env).decode().strip()
commit=git('commit-tree',tree,'-p',parent,data=b'Clarify pre-merge and final B3 evidence revisions\n\nCo-Authored-By: Codex <noreply@openai.com>\n').decode().strip()
for name in ['README.md','archive-update.py']:
 assert hashlib.sha256(git('show',commit+':'+name)).hexdigest()==manifest[name]
git('update-ref',ref,commit,parent)
print(commit)
