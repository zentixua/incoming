#!/usr/bin/env python3
"""PERFA: additional plans for tools/stand; fresh, retained client per run. No publication."""
import argparse, fnmatch, hashlib, json, math, os, shlex, shutil, subprocess, sys, time
from pathlib import Path
ROOT=Path('/mnt/data/projects/incoming/mod/run/wt-PERFA')
OUT=Path('/mnt/data/projects/incoming/mod/run/wt-PERF/mod/run/perf-work/runtime')
RUN=OUT/'data'
sys.path.insert(0,str(ROOT/'tools/stand'))
import client, plan
BASE=Path('/mnt/data/projects/incoming/mod/run/stand/dl')
MC=Path.home()/'.var/app/org.prismlauncher.PrismLauncher/data/PrismLauncher/instances/Open Skies/minecraft'
def cmd(c):return {'do':'cmd','cmd':c}
def wait(s):return {'do':'wait','s':s}
def window(n,s):return {'do':'window','name':n,'s':s}
def shot(n):return {'do':'shot','name':n}
def tp(x,y,z,lx,ly,lz,s=3):return {'do':'tp','eye':[x,y,z],'look':[lx,ly,lz],'settle_s':s}
def city_steps(x):
 steps=[]
 steps += [cmd(f'forceload add {x-160} -160 {x+160} 0'),cmd(f'forceload add {x-160} 16 {x+160} 160'),wait(5)]
 for xx in range(-120,121,40):
  for zz in range(-120,121,40):
   bx=x+xx
   steps += [cmd(f'fill {bx-9} 65 {zz-9} {bx+9} 80 {zz+9} stone_bricks hollow'),
             cmd(f'fill {bx-8} 80 {zz-8} {bx+8} 80 {zz+8} oak_planks'),
             cmd(f'fill {bx-7} 68 {zz-9} {bx+7} 72 {zz-9} glass'),
             cmd(f'fill {bx-7} 68 {zz+9} {bx+7} 72 {zz+9} glass'),
             cmd(f'setblock {bx} 79 {zz} sea_lantern')]
 steps += [wait(10)]
 return steps
def make_plan(a):
 s=plan.load(); steps=[]
 specs=[(int(n),r) for n in a.counts.split(',') for r in a.spreads.split(',') if not(int(n)==1 and r=='spread')]
 for k,(n,r) in enumerate(specs):
  x=k*2048; z=0; rad=100 if r=='spread' else 0; name=f'{a.kind}-{a.terrain}-{a.light}-{n}-{r}'
  steps += [tp(x-180,95,z,x,80,z,4),cmd('time set '+('6000' if a.light=='day' else '18000'))]
  if a.terrain=='city':steps += city_steps(x)
  steps += [shot(name+'-before'),window(name+'/baseline',10)]
  if a.profile in ('spark','both','alloc'):steps += [cmd('spark profiler start --thread * '+('--alloc' if a.profile=='alloc' else '--interval 2'))]
  if a.kind=='incoming':
   pts=plan.aim_points((x,z),rad,n,0)
   commands,delays,_=plan.strike_commands(s,(x,z),pts,0,10)
   steps += [{'do':'salvo','id':name,'x':x,'z':z,'radius':rad+45,'expect':n,'cmds':commands,'delays':delays},
             {'do':'impact_window','name':name+'/impact','salvo':name,'n':1,'s':40,'shots':[0.3,3,15,35], 'prefix':name,'timeout_s':150},
             {'do':'await','salvo':name,'timeout_s':90,'quiet_s':10},window(name+'/aftermath',15),shot(name+'-after')]
  elif a.kind=='nuke':
   # Surface 15 kt 1.2 km from camera; the city fixture lies at the camera where the wave arrives.
   steps += [cmd(f'forceload add {x+1200} 0'),wait(3),cmd(f'incoming nuke now at {x+1200} 65 0 15 ground'),
             window(name+'/flash',3),shot(name+'-3s'),window(name+'/wave',32),shot(name+'-35s'),
             window(name+'/aftermath',35),shot(name+'-70s')]
  if a.profile in ('spark','both','alloc'):steps += [cmd('spark profiler stop --save-to-file'),wait(2)]
  if a.terrain=='city':steps += [cmd('forceload remove all')]
 steps += [wait(3)]
 return dict(seed=20261025,day_time=6000 if a.light=='day' else 18000,settle_s=5,limit_s=3600,
   projectiles=['incoming:drone'],warheads=['incoming:strike'],probes=s['probes']+['ua.zentix.incoming.client.sound.BlastSounds#describe','ua.zentix.incoming.client.sound.ClientSounds#describe','ua.zentix.incoming.client.fx.FireSmoke#describe','ua.zentix.incoming.client.nuclear.NukeSounds#describe','ua.zentix.incoming.client.nuclear.NukePall#describe'],once=s['once'],steps=steps)

def main():
 # Reuse the audited, already-built harness without changing its source or artifacts.
 p=argparse.ArgumentParser();p.add_argument('name');p.add_argument('--kind',default='incoming');p.add_argument('--counts',default='1,10,50,100');p.add_argument('--spreads',default='point,spread');p.add_argument('--terrain',default='field');p.add_argument('--light',default='day');p.add_argument('--pack',default='none');p.add_argument('--profile',default='none');p.add_argument('--prop',action='append',default=[]);p.add_argument('--server-config',default='{}');p.add_argument('--client-config',default='{}');p.add_argument('--extra-mod',action='append',default=[]);p.add_argument('--exclude-mod',action='append',default=[]);p.add_argument('--plan');p.add_argument('--jar');p.add_argument('--prepare-only',action='store_true');p.add_argument('--modpack');p.add_argument('--production',action='store_true');p.add_argument('--renderdoc-window');a=p.parse_args()
 game=RUN/'runs'/a.name
 if game.exists():raise SystemExit(f'Refusing to overwrite retained run {game}')
 (game/'mods').mkdir(parents=True);(game/'config/spark').mkdir(parents=True)
 if a.modpack:
  src=MC if a.modpack=='host' else Path(a.modpack)
  for folder in ('config','shaderpacks','resourcepacks'):
   if (src/folder).exists():shutil.copytree(src/folder,game/folder,dirs_exist_ok=True)
  sys.path.insert(0,str(ROOT/'tools'));import prod_client
  prod_client.no_idle(str(game))
 jars=[Path(a.jar) if a.jar else ROOT/'mod/build/libs/incoming-3.10.4.jar',*sorted(((src/'mods') if a.modpack else (BASE/'mods')).glob('*.jar'))]
 jars=[j for i,j in enumerate(jars) if i==0 or not j.name.startswith('incoming-')]
 jars=[j for j in jars if not any(fnmatch.fnmatch(j.name,p) for p in a.exclude_mod)]
 if a.profile in ('spark','both','alloc'):jars+=list((MC/'mods').glob('spark-*.jar'))
 jars += [Path(f) for f in a.extra_mod]
 for j in jars:shutil.copy2(j,game/'mods'/j.name)
 if a.pack!='none':
  packs=game/'shaderpacks';packs.mkdir(exist_ok=True); source=BASE/'shaderpacks/ComplementaryUnbound_r5.9.3.zip'
  shutil.copy2(source,packs/source.name)
  # Copy the player's shader preset for exactly this pack, read-only source.
  settings=MC/'shaderpacks'/f'{source.name}.txt'
  if settings.exists():shutil.copy2(settings,packs/settings.name)
  iris=f'enableShaders=true\nshaderPack={source.name}\ndisableUpdateMessage=true\n'
 else:iris='enableShaders=false\ndisableUpdateMessage=true\n'
 (game/'config/iris.properties').write_text(iris)
 if not a.modpack and any(j.name.startswith('DistantHorizons-') for j in jars):
  # Match the saved-terrain LOD fixture; do not benchmark unrelated infinite flat-world generation.
  (game/'config/DistantHorizons.toml').write_text('[common.worldGenerator]\nchunkGeneratorMode = \"PRE_EXISTING_ONLY\"\ngeneratorPlan = \"CHUNKS_ONLY\"\n[client.advanced.autoUpdater]\nenableAutoUpdater = false\nenableSilentUpdates = false\n')
 (game/'config/oppencraft-ui.properties').write_text('companionNoticeDismissed=true\n')
 (game/'config/spark/config.json').write_text(json.dumps({'backgroundProfiler':False}))
 cfg={'launch':{'drone_flight_time':5,'missile_flight_time':5,'max_salvo':100}}
 for sec,vals in json.loads(a.server_config).items():cfg.setdefault(sec,{}).update(vals)
 (game/'config/incoming-server.toml').write_text(client.server_toml(cfg))
 (game/'config/incoming-client.toml').write_text(''.join(f'{k} = {json.dumps(v)}\n' for k,v in json.loads(a.client_config).items()))
 (game/'options.txt').write_text(client.OPTIONS)
 (game/'alsoft.conf').write_text('[general]\ndrivers = null\n')
 q=json.loads(Path(a.plan).read_text()) if a.plan else make_plan(a)
 if 'ua.zentix.incoming.client.sound.OwnSources#describe' not in q['probes']:
  q['probes'].append('ua.zentix.incoming.client.sound.OwnSources#describe')
 # Spark starts asynchronously. The ready log is checked afterward; keep its startup outside the strike.
 if a.profile in ('spark','both','alloc'):
  steps=[]
  for step in q['steps']:
   steps.append(step)
   if step.get('cmd','').startswith('spark profiler start'):steps.append(wait(5))
  q['steps']=steps
 if a.profile!='none' or a.renderdoc_window:q['probes'] += ['ua.zentix.stand.AuditProbes#particles','ua.zentix.stand.AuditProbes#particleClasses','ua.zentix.stand.AuditProbes#oppencraft']
 if any(p.startswith('perfa.no') for p in a.prop):q['probes'] += ['ua.zentix.stand.AuditAblations#describe','ua.zentix.stand.AuditProbes#particles','ua.zentix.stand.AuditProbes#particleClasses']
 (game/'stand-plan.json').write_text(json.dumps(q,indent=2))
 (game/'config-manifest.json').write_text(json.dumps({str(f.relative_to(game)):hashlib.sha256(f.read_bytes()).hexdigest() for folder in ['config','shaderpacks'] for f in (game/folder).rglob('*') if f.is_file()},indent=2))
 (game/'manifest.json').write_text(json.dumps({'args':vars(a),'main':'341c2ea6f','utc':time.strftime('%Y-%m-%dT%H:%M:%SZ',time.gmtime()),'mods':{j.name:hashlib.sha256(j.read_bytes()).hexdigest() for j in jars},'power_profile':client.power_profile(),'stand_jar_sha256':hashlib.sha256(next((ROOT/'tools/stand/build/libs').glob('*.jar')).read_bytes()).hexdigest()},indent=2))
 launch=ROOT/'tools/stand/build/launch'
 script=(launch/'client.sh').read_text().replace('/mnt/data/projects/incoming/mod/run/perf-audit/data/game-base',str(game))
 for f in launch.glob('*.txt'):
  shutil.copy2(f,game/f.name);script=script.replace(str(f),str(game/f.name))
 java=str(Path.home()/'.var/app/org.prismlauncher.PrismLauncher/data/PrismLauncher/java/java-runtime-delta/bin/java')
 flags=([f'-Dperfa.counts=true'] if a.profile!='none' else [])+[f'-D{k}' for k in a.prop]+['-Duser.timezone=UTC',f'-Xlog:gc:file={game}/gc.log:time,uptime']
 if a.profile in ('jfr','both','async'):flags += [f'-XX:StartFlightRecording=filename={game}/profile.jfr,settings=profile,dumponexit=true,maxsize=1g','-XX:FlightRecorderOptions=stackdepth=256']
 if a.profile=='async':flags += [f'-agentpath:/mnt/data/projects/incoming/mod/run/perf-audit/data/tooling/async-profiler-4.6-linux-x64/lib/libasyncProfiler.so=start,event=cpu,interval=2ms,cstack=dwarf,file={game}/async.jfr,log={game}/async-profiler.log']
 script=script.replace("'"+java+"'",shlex.quote(java)+' '+' '.join(map(shlex.quote,flags)),1)
 if a.production:
  sys.path.insert(0,str(ROOT/'tools'));import prod_client
  standjar=next((ROOT/'tools/stand/build/libs').glob('*.jar'));shutil.copy2(standjar,game/'mods'/standjar.name)
  (game/'logs').mkdir(exist_ok=True)
  args=prod_client.launch_args(str(game),{},'Dev',['--width','2560','--height','1600'],extra_jvm=flags)
  script='#!/bin/bash\ncd '+shlex.quote(str(game))+'\nexec '+shlex.join([java,*args])+'\n'
 if not a.renderdoc_window:
  lines=script.splitlines();lines.insert(len(lines)-1,'echo $$ > '+shlex.quote(str(game/'java.pid')));script='\n'.join(lines)+'\n'
 if a.renderdoc_window:
  rd=Path('/mnt/data/projects/incoming/mod/run/renderdoc-dist/renderdoc_1.46');old=RUN/'scripts/renderdoc'
  env={'RD_EVENTS':str(game/'stand-out/events.jsonl'),'RD_WINDOW':a.renderdoc_window,'RD_DELAY':'5','RD_LOG':str(game/'rd.log'),'RD_GAME':str(game)}
  lines=script.splitlines(); launchline=lines.pop().removeprefix('exec ').removeprefix('env  ')
  rd_env=['env','QT_QPA_PLATFORM=xcb','XDG_DATA_HOME='+str(RUN/'renderdoc-private')]
  lines += ['export '+ ' '.join(k+'='+shlex.quote(v) for k,v in env.items()),shlex.join([*rd_env,str(rd/'bin/qrenderdoc'),'--python',str(old/'trigger.py')])+' > '+shlex.quote(str(game/'rd-trigger.log'))+' 2>&1 &',shlex.join([str(rd/'bin/renderdoccmd'),'capture','-w','-d',str(game),'-c',str(game/'frame')])+' '+launchline]
  lines += ['for cap in '+shlex.quote(str(game/'frame'))+'*.rdc; do', '[ -f \"$cap\" ] || continue', 'export RD_CAP=\"$cap\" RD_OUT='+shlex.quote(str(game/'rd-analysis')),shlex.join([*rd_env,'timeout','600',str(rd/'bin/qrenderdoc'),'--python',str(old/'analyze.py')])+' > '+shlex.quote(str(game/'rd-analyze.log'))+' 2>&1',shlex.join([*rd_env,'timeout','300',str(rd/'bin/qrenderdoc'),'--python',str(old/'repeat.py')])+' > '+shlex.quote(str(game/'rd-repeat.log'))+' 2>&1','break','done']
  script='\n'.join(lines)+'\n'
 (game/'launch.sh').write_text(script)
 if a.prepare_only:return
 client.SOCKET='wayland-incoming-perfa'
 start=time.time()
 with (game/'console.log').open('w') as log:
  code=client.run(game,game/'launch.sh','2560x1600',q['limit_s'],log,lambda *s:print(*s,flush=True),quiet=True)
 if a.renderdoc_window and not list(game.glob('frame*.rdc')):code=67
 elif a.renderdoc_window and not (game/'rd-analysis.json').exists():code=68
 if a.renderdoc_window and (game/'rd-analysis.json').exists() and not any(r.get('gpu_s') is not None and r['gpu_s']>0 for r in json.loads((game/'rd-analysis.json').read_text())):code=69
 if a.profile=='async' and not (game/'async.jfr').exists():code=65
 if a.profile in ('spark','both','alloc') and not list(game.rglob('*.sparkprofile')):code=66
 (game/'exit.json').write_text(json.dumps({'code':code,'start_epoch':start,'end_epoch':time.time()}))
 subprocess.run(['journalctl','--user','--since','@'+str(int(start)),'-o','cat'],stdout=(game/'journal.log').open('w'),stderr=subprocess.DEVNULL)
 print('PERFA',a.name,'exit',code,flush=True)
 return code
if __name__=='__main__':sys.exit(main())
