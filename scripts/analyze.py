#!/usr/bin/env python3
"""Raw tools/stand frames and exact server ticks -> PERFA windows. Nearest-rank percentiles."""
import csv,json,math,sys,statistics
from pathlib import Path
def stats(v):
 v=sorted(x for x in v if x is not None and math.isfinite(x))
 if not v:return None
 return dict(n=len(v),p50=v[math.ceil(.5*len(v))-1],p95=v[math.ceil(.95*len(v))-1],p99=v[math.ceil(.99*len(v))-1],max=v[-1],mean=statistics.mean(v),over33=sum(x>33.333 for x in v),over50=sum(x>50 for x in v),over100=sum(x>100 for x in v),over250=sum(x>250 for x in v))
def analyze(game):
 ev=[]
 for line in (game/'stand-out/events.jsonl').read_text().splitlines():
  try:ev.append(json.loads(line))
  except json.JSONDecodeError:pass
 frames=list(csv.DictReader((game/'stand-out/frames.csv').open()))
 for f in frames:
  for k in ('t_ms','prev_interval_ms','cpu_ms','cpu_thread_ms','gpu_ms','prev_gpu_period_ms','shot'):
   f[k]=float(f[k]) if f[k] else None
 for i,f in enumerate(frames):
  nxt=frames[i+1] if i+1<len(frames) else None
  f['frame_interval_ms']=nxt['prev_interval_ms'] if nxt and f['shot']==0 else None
  f['cycle_cpu_ms']=float(nxt['prev_cpu_cycle_ms']) if nxt and nxt.get('prev_cpu_cycle_ms') and f['shot']==0 else None
 # A screenshot happens at the next frame's Post, so its flag must not censor
 # the preceding Pre-to-Pre interval. Exclude the captured frame and its successor only.
 # Flight windows also include launch/approach and the whole scene, derived from recorded events.
 # Chunk loading or trail work before the first hit must not disappear from the audit.
 derived=[]
 for launch in [e for e in ev if e['type']=='salvo']:
  hits=[e['t_ms'] for e in ev if e['type']=='impact' and e['salvo']==launch['id']]
  ends=[e['t_ms'] for e in ev if e['type']=='window' and e['edge']=='end' and e['name']==launch['id']+'/aftermath']
  if hits:
   for suffix,end in [('approach',min(hits)),('full',max(ends) if ends else max(hits))]:
    if end>launch['t_ms']:
     derived += [{'type':'window','name':launch['id']+'/'+suffix,'edge':'begin','t_ms':launch['t_ms']},{'type':'window','name':launch['id']+'/'+suffix,'edge':'end','t_ms':end}]
   if len(hits)>1 and max(hits)>min(hits):
    derived += [{'type':'window','name':launch['id']+'/arrivals','edge':'begin','t_ms':min(hits)},{'type':'window','name':launch['id']+'/arrivals','edge':'end','t_ms':max(hits)+1}]
 # Immediate nuclear scenes: retain phase detail plus the whole visible-effects interval.
 flash=[e['t_ms'] for e in ev if e['type']=='window' and e['edge']=='begin' and e['name'].endswith('/flash')]
 late=[e['t_ms'] for e in ev if e['type']=='window' and e['edge']=='end' and e['name']=='nuke/late70-180']
 if len(flash)==1 and len(late)==1:
  derived += [{'type':'window','name':'nuke/effects-total','edge':'begin','t_ms':flash[0]},{'type':'window','name':'nuke/effects-total','edge':'end','t_ms':late[0]}]
 timeline=game/'nuclear-timeline.json'
 if timeline.exists():
  nt=json.loads(timeline.read_text())
  if nt['valid_single_launch_and_detonation']:
   at=nt['detonations'][0]['t_ms']
   for name,lo,hi in [('flash',0,3),('wave',3,35),('aftermath',35,70),('late70-180',70,180),('effects-total',0,180)]:
    derived += [{'type':'window','name':'nuke-flight-detonation/'+name,'edge':'begin','t_ms':at+lo*1000},{'type':'window','name':'nuke-flight-detonation/'+name,'edge':'end','t_ms':at+hi*1000}]
 ev += derived
 wins={}; out={}
 for e in ev:
  if e['type']=='window':
   if e['edge']=='begin':wins[e['name']]=e['t_ms']
   elif e['name'] in wins:
    lo,hi=wins[e['name']],e['t_ms']; fs=[f for f in frames if lo<=f['t_ms']<hi and f['shot']==0]
    ticks=[f for f in ev if f['type']=='tick' and lo<=f['t_ms']<hi]
    out[e['name']]={'start_ms':lo,'end_ms':hi,'frame':stats([f['frame_interval_ms'] for f in fs]),'cpu':stats([f['cpu_thread_ms'] for f in fs]),'gpu_elapsed':stats([f['gpu_ms'] for f in fs]),'tick':stats([f['wall_ms'] for f in ticks]),'tick_cpu':stats([f['cpu_ms'] for f in ticks]),'fx_gpu_elapsed':stats([float(z.split('=')[1]) for f in fs for z in f['stages'].split(';') if z.startswith('after_level+=')]),'probes':[f for f in ev if f['type']=='probe' and lo<=f['t_ms']<hi]}
 for prefix in {k.rsplit('/',1)[0] for k in out if '/t0-3' in k}:
  names=[k for k in out if k.startswith(prefix+'/t')]
  lo=min(out[k]['start_ms'] for k in names);hi=max(out[k]['end_ms'] for k in names)
  fs=[f for f in frames if lo<=f['t_ms']<hi and f['shot']==0];ts=[e for e in ev if e['type']=='tick' and lo<=e['t_ms']<hi]
  out[prefix+'/total']={'start_ms':lo,'end_ms':hi,'frame':stats([f['frame_interval_ms'] for f in fs]),'cpu':stats([f['cpu_thread_ms'] for f in fs]),'gpu_elapsed':stats([f['gpu_ms'] for f in fs]),'tick':stats([f['wall_ms'] for f in ts]),'tick_cpu':stats([f['cpu_ms'] for f in ts])}
 result={'run':game.name,'windows':out,'issues':[e for e in ev if e['type'] in ('tp-timeout','limit','timeout','late','impact-timeout') or (e['type']=='await' and (e.get('why')=='timeout' or e.get('impacts',0)<e.get('expect',0))) or (e['type']=='cmd' and not e.get('ok',True)) or (e['type']=='client-command' and not e.get('handled',False))], 'impacts':{name:sum(e['type']=='impact' and e['salvo']==name for e in ev) for name in {e['salvo'] for e in ev if e['type']=='impact'}},'gl':[e for e in ev if e['type']=='gl'],'once':[e for e in ev if e['type']=='once']}
 for w in out.values():
  w['cycle_cpu']=stats([f['cycle_cpu_ms'] for f in frames if w['start_ms']<=f['t_ms']<w['end_ms']])
 (game/'summary.json').write_text(json.dumps(result,indent=2))
 for name,w in out.items():
  f=w['frame'];t=w['tick'];c=w['cpu'];g=w['gpu_elapsed']
  def show(v,keys):return '/'.join(f'{v[k]:.3f}' for k in keys) if v else 'no observations'
  print(game.name,name,'frame',show(f,['p50','p99','max']),'>50',f['over50'] if f else None,'CPU',show(c,['p50']),'GPU',show(g,['p50']),'tick',show(t,['p50','p99']))
 print('issues',result['issues'],'impacts',result['impacts'])
 return result
if __name__=='__main__':
 for n in sys.argv[1:]:analyze(Path(n))
