"""Export four bounded cycles from the five-second current native recording."""
from pathlib import Path
import json,csv,statistics,subprocess,os,math,re,collections,sys
D=Path(__file__).resolve().parent;g=D/'runs/current-native'
e=[json.loads(x)for x in(g/'stand-out/events.jsonl').read_text().splitlines()]
origin=statistics.median(x['epoch_ms']-x['t_ms']for x in e if x['type']=='srv')
mark=next(x['t_ms']for x in e if x['type']=='mark'and x['name'].endswith('/native-begin'))
r=list(csv.DictReader((g/'stand-out/frames.csv').open()))
for i,f in enumerate(r[:-1]):
 f['t']=float(f['t_ms']);f['interval']=float(r[i+1]['prev_interval_ms']);f['cycle_cpu']=float(r[i+1]['prev_cpu_cycle_ms'])
idx=max((i for i,f in enumerate(r[:-1])if mark-1000<=f['t']<mark+3000),key=lambda i:r[i]['interval'])
ordinary=max((i for i,f in enumerate(r[:-1])if mark-900<=f['t']<mark-100 and f['interval']<25),key=lambda i:r[i]['t'])
clips=[('peak',idx),('previous1',idx-1),('previous2',idx-2),('ordinary',ordinary)]
java=Path.home()/'.var/app/org.prismlauncher.PrismLauncher/data/PrismLauncher/java/java-runtime-delta';env=dict(os.environ,JAVA_HOME=str(java));conv=D/'tooling/async-profiler-4.6-linux-x64/bin/jfrconv'
summaries_only='--summaries-only' in sys.argv
game_thread=None
out={'origin_epoch_ms':origin,'native_begin_t_ms':mark,'capture':json.loads((g/'native-capture.json').read_text()),'clips':{}}
for name,i in clips:
 f=r[i];lo=round(origin+f['t']);hi=round(origin+f['t']+f['interval']);p=g/f'current-{name}.collapsed'
 if not summaries_only:subprocess.run([str(conv),'--cpu','--threads','--dot','--from',str(lo),'--to',str(hi),'-o','collapsed',str(g/'async.jfr'),str(p)],env=env,check=True)
 threads=collections.Counter();inclusive=collections.Counter();leaves=collections.Counter();branches=collections.Counter();render=0
 lines=p.read_text().splitlines()
 if game_thread is None:
  roots={line.split(';',1)[0]for line in lines if ';net.minecraft.client.Minecraft.run_'in line}
  assert len(roots)==1,roots
  game_thread=next(iter(roots));out['game_thread']=game_thread
 for line in lines:
  stack,n=line.rsplit(' ',1);n=int(n);parts=stack.split(';');threads[parts[0]]+=n
  if parts[0]!=game_thread:continue
  render+=n;names=[re.sub(r'_\[[^]]+\]$','',s)for s in parts[1:]]
  for s in set(names):inclusive[s]+=n
  leaves[names[-1]]+=n
  if 'net.minecraft.client.Minecraft.tick' in stack:
   if 'net.minecraft.client.particle.ParticleEngine.tick' in stack:b='tick/ParticleEngine'
   elif 'ua.zentix.incoming.client.fx.Effects.'in stack or'BlastEffects$Timeline.'in stack:b='tick/Effects'
   elif 'ua.zentix.stand.Probes.'in stack:b='tick/probes'
   elif 'ua.zentix.incoming.client.IncomingClient.tick'in stack:b='tick/otherIncomingClient'
   else:b='tick/other'
  elif'net.minecraft.client.renderer.GameRenderer.render'in stack:
   b='render/smoke'if'SmokePreparation.draw'in stack else'render/otherFX'if'ua.zentix.incoming.client.fx.layer.FxLayer.'in stack else'render/otherWorld'
  elif'net.minecraft.client.Minecraft.runAllTasks'in stack or'net.minecraft.util.thread.BlockableEventLoop.'in stack:b='pollTasks'
  else:b='other'
  branches[b]+=n
 o={'frame':f['frame'],'start_t_ms':f['t'],'relative_native_ms':f['t']-mark,'interval_ms':f['interval'],'full_cycle_cpu_ms':f['cycle_cpu'],'render_wall_ms':float(f['cpu_ms']),'render_cpu_ms':float(f['cpu_thread_ms']),'from_epoch_ms':lo,'to_epoch_ms':hi,'render_samples':render,'threads':dict(threads),'disjoint_render_branches':dict(branches),'inclusive':dict(inclusive.most_common()),'leaves':dict(leaves.most_common())};out['clips'][name]=o
 print(name,'frame',f['frame'],'interval',f['interval'],'CPU',f['cycle_cpu'],'render',f['cpu_thread_ms'],'samples',render,'branches',dict(branches),flush=True)
 print('own',[(s,n)for s,n in inclusive.most_common()if'ua.zentix.'in s][:15],flush=True);print('leaves',leaves.most_common(8),flush=True)
(g/'current-clips.json').write_text(json.dumps(out,indent=2))
if not summaries_only:subprocess.run([str(java/'bin/java'),'/mnt/data/projects/incoming/mod/run/perf-audit/data/scripts/JfrAgg.java',str(g/'profile.jfr'),str(g/'jfr')],env=env,check=True)
