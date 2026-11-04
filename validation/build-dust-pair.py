"""Same-JDK candidate and main-equivalent GroundDust family, identical remaining JAR bytes."""
from pathlib import Path
import subprocess,zipfile,hashlib,json,shutil
D=Path(__file__).resolve().parent;root=D.parents[2];base='1126ffa3bf3f5f2c8eee1b2340da49c2a5663f98';head=subprocess.check_output(['git','rev-parse','HEAD'],text=True).strip()
changes=subprocess.check_output(['git','diff','--name-only',base,head,'--','mod/src/main'],text=True).splitlines();assert changes==['mod/src/main/java/ua/zentix/incoming/client/fx/smoke/GroundDust.java'],changes
src=D/'dust-baseline-src/ua/zentix/incoming/client/fx/smoke/GroundDust.java';src.parent.mkdir(parents=True,exist_ok=True);src.write_bytes(subprocess.check_output(['git','show',base+':mod/src/main/java/ua/zentix/incoming/client/fx/smoke/GroundDust.java']))
classes=D/'dust-baseline-classes';classes.mkdir(exist_ok=True)
java=Path.home()/'.var/app/org.prismlauncher.PrismLauncher/data/PrismLauncher/java/java-runtime-delta/bin/javac'
subprocess.run([str(java),'--release','21','-g','-encoding','UTF-8','-proc:none','-cp',str(root/'mod/build/classes/java/main'),'-d',str(classes),str(src)],check=True)
pair=D/'dust-pair';(pair/'candidate').mkdir(parents=True,exist_ok=True);(pair/'main').mkdir(parents=True,exist_ok=True)
candidate=pair/'candidate/incoming-3.10.4.jar';shutil.copy2(root/'mod/build/libs/incoming-3.10.4.jar',candidate)
old={str(p.relative_to(classes)):p.read_bytes()for p in classes.rglob('*.class')}
with zipfile.ZipFile(candidate)as z,zipfile.ZipFile(pair/'main/incoming-3.10.4.jar','w',compression=zipfile.ZIP_DEFLATED)as out:
 for info in z.infolist():
  if info.filename.startswith('ua/zentix/incoming/client/fx/smoke/GroundDust')and info.filename.endswith('.class'):continue
  out.writestr(info,z.read(info.filename))
 for n,data in old.items():out.writestr(n,data)
with zipfile.ZipFile(candidate)as a,zipfile.ZipFile(pair/'main/incoming-3.10.4.jar')as b:
 changed=sorted(n for n in set(a.namelist())|set(b.namelist())if n not in a.namelist()or n not in b.namelist()or a.read(n)!=b.read(n));assert all(n.startswith('ua/zentix/incoming/client/fx/smoke/GroundDust')for n in changed),changed
meta={'base':base,'candidate':head,'baseline_method':'Candidate jar with entire GroundDust family compiled from pinned main using same JDK21; unchanged entries byte-identical','changed_payloads':changed,'jars':{n:hashlib.sha256((pair/n/'incoming-3.10.4.jar').read_bytes()).hexdigest()for n in ['main','candidate']}}
(pair/'pair.json').write_text(json.dumps(meta,indent=2));print(json.dumps(meta,indent=2))
