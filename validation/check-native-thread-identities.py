from pathlib import Path
import collections,json
D=Path(__file__).resolve().parent
out={}
for run in ['main-profile','main-ap-profile']:
 g=D/'runs'/run;summary={}
 for name in ['async.jfr.cpu.collapsed','native-longest-frame.collapsed']:
  p=g/name;roots=set()
  with p.open()as f:
   for line in f:
    if ';net.minecraft.client.Minecraft.run_' in line:roots.add(line.split(';',1)[0])
  assert len(roots)==1,roots
  root=next(iter(roots));threads=collections.Counter();count=probe=0
  with p.open()as f:
   for line in f:
    stack,n=line.rsplit(' ',1);n=int(n);t=stack.split(';',1)[0];threads[t]+=n
    if t==root:
     count+=n
     if 'ua.zentix.incoming.client.fx.layer.SceneDepth.lodShare' in stack:probe+=n
  summary[name]={'game_thread':root,'game_samples':count,'lodShare_samples':probe,'threads':dict(threads)}
 out[run]=summary
p=D/'legacy-native-thread-identities.json';p.write_text(json.dumps(out,indent=2));print(json.dumps(out,indent=2))
