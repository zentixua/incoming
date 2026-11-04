"""Disposable updater tests. Never opens Minecraft or writes actual targets."""
from pathlib import Path
import hashlib,json,shutil,subprocess,zipfile,tomllib,os
W=Path(__file__).resolve().parent
JAVA=Path('/home/artem/.var/app/org.prismlauncher.PrismLauncher/data/PrismLauncher/java/java-runtime-delta/bin/java')
SHA='0de8fb1cc9a484d6e9c92716a8f782c921359c3f07e61decb61eea651a7333d8'
JAR='mods/incoming-3.11.0.jar'
def hashes(root):
 return {str(f.relative_to(root)):hashlib.sha256(f.read_bytes()).hexdigest() for f in sorted(root.rglob('*')) if f.is_file()}
def copy(src,dst):
 assert not dst.exists(),dst
 subprocess.run(['cp','-a','--reflink=auto',str(src),str(dst)],check=True)
def run(cwd,args,log,ok=True):
 with (W/log).open('wb') as f:r=subprocess.run(args,cwd=cwd,stdout=f,stderr=subprocess.STDOUT,timeout=180)
 if ok:assert r.returncode==0,(log,r.returncode)
 return r.returncode
def state(root):
 h=hashes(root)
 return {k:v for k,v in h.items() if k=='packwiz.json' or k.startswith('mods/') or k.startswith('config/') or k.startswith('options') or k in ['start.sh','user_jvm_args.txt','server.properties']}
def inventory(root):
 out={}
 incoming=[]
 for f in sorted((root/'mods').glob('*.jar')):
  with zipfile.ZipFile(f) as z:
   if 'META-INF/neoforge.mods.toml' in z.namelist():
    for m in tomllib.loads(z.read('META-INF/neoforge.mods.toml').decode()).get('mods',[]):
     if m['modId']=='incoming':incoming.append(f.name)
  out[f.name]=hashlib.sha256(f.read_bytes()).hexdigest()
 assert incoming==['incoming-3.11.0.jar'],incoming
 assert out['incoming-3.11.0.jar']==SHA
 return out
receipt={}
for side in ['client','server']:
 original=W/f'original-{side}'; root=W/f'scratch-{side}'
 copy(original,root)
 before=state(root);manifest=json.loads((root/'packwiz.json').read_text())
 # Move every old Incoming destination before installer manifest cleanup.
 old=manifest['cachedFiles']['mods/incoming.pw.toml']['cachedLocation']
 (root/'preserved-originals').mkdir()
 shutil.move(root/old,root/'preserved-originals'/Path(old).name)
 copy(W/'pin-b561',root/'private-pin')
 (root/'private-pin/installed.sha256').write_text(f'{SHA}  {JAR}\n')
 hook='''#!/usr/bin/env bash
set -euo pipefail
cd -- "$(dirname -- "$0")"
"${1:?Java path required}" -jar packwiz-installer-bootstrap.jar --bootstrap-no-update -g -s SIDE ./private-pin/pack.toml
sha256sum -c private-pin/installed.sha256
'''.replace('SIDE',side)
 (root/'private-prelaunch.sh').write_text(hook)
 cmds=['bash','private-prelaunch.sh',str(JAVA)]
 run(root,cmds,f'{side}-pass1.log');first=state(root)
 inv=inventory(root)
 run(root,cmds,f'{side}-pass2.log');second=state(root)
 assert first==second,side+' changed on second pass'
 expected=set(before)-{old}
 assert set(first)==expected|{JAR},(side,set(first)^expected)
 for k in expected:assert first[k]==before[k] or k=='packwiz.json',(side,k)
 newmanifest=json.loads((root/'packwiz.json').read_text())
 assert len(manifest['cachedFiles'])==len(newmanifest['cachedFiles'])
 for k,v in manifest['cachedFiles'].items():
  if k=='mods/incoming.pw.toml':continue
  assert v.get('optionValue')==newmanifest['cachedFiles'][k].get('optionValue'),(side,k)
 assert 'mods/incoming.pw.toml' not in newmanifest['cachedFiles']
 assert JAR in newmanifest['cachedFiles']
 # Fail-closed hook boundary: append fake launch, which must stay unreachable on failure.
 guard=root/'guard-with-sentinel.sh'
 guard.write_text(hook+'touch GAME_WOULD_LAUNCH\n')
 results={}
 for case in ['corrupt-installed','missing-installed-repair','missing-source-and-installed','corrupt-source-and-missing-installed']:
  neg=W/f'{side}-{case}';copy(root,neg)
  installed=neg/JAR; source=neg/'private-pin'/JAR
  if case=='corrupt-installed':installed.write_bytes(b'corrupt installed candidate\n')
  else:shutil.move(installed,neg/'preserved-originals/missing-installed.jar')
  if case=='missing-source-and-installed':shutil.move(source,neg/'preserved-originals/missing-source.jar')
  if case=='corrupt-source-and-missing-installed':
   shutil.move(source,neg/'preserved-originals/source.jar');source.write_bytes(b'corrupt source candidate\n')
  rc=run(neg,['bash','guard-with-sentinel.sh',str(JAVA)],f'{side}-{case}.log',ok=False)
  launch=(neg/'GAME_WOULD_LAUNCH').exists()
  if case=='missing-installed-repair':assert rc==0 and launch and hashlib.sha256((neg/JAR).read_bytes()).hexdigest()==SHA
  else:assert rc!=0 and not launch,(side,case,rc,launch)
  results[case]={'exit':rc,'launch_sentinel':launch}
 receipt[side]={'before':before,'after':first,'inventory':inv,'optional_choices_preserved':True,'two_pass_state_identical':True,'negative_guards':results,'manifest_sha256':first['packwiz.json']}
 (W/'receipts.json').write_text(json.dumps(receipt,indent=2)+'\n')
 print(side,'PASS',len(inv),'jars; settings preserved; two passes identical; guards',results,flush=True)
