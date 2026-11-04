import csv,json,statistics
from pathlib import Path
root=Path('/mnt/data/projects/incoming/mod/run/stand/out/ol2-first')
result={}
for run in ['A1','B','A2']:
 p=root/run/'none'
 es=[json.loads(s) for s in (p/'events.jsonl').read_text().splitlines()]
 hs=[json.loads(s) for s in (p/'host.jsonl').read_text().splitlines()]
 frames=list(csv.DictReader((p/'frames.csv').open()))
 srv=next(e for e in es if e['type']=='srv')
 offset=srv['epoch_ms']/1000-srv['t_ms']/1000
 wins={}
 for e in es:
  if e['type']=='window':wins.setdefault(e['name'],{})[e['edge']]=e['t_ms']
 summary={}
 for name,w in wins.items():
  selected=[f for f in frames if w['begin']<=float(f['t_ms'])<=w['end']]
  def pct(key,p):
   a=sorted(float(f[key]) for f in selected if f[key])
   x=p*(len(a)-1);i=int(x)
   return a[i]+(a[min(i+1,len(a)-1)]-a[i])*(x-i)
  cpu=[h for h in hs if h['kind']=='cpu' and offset+w['begin']/1000<=h['epoch']<=offset+w['end']/1000]
  gpu=[h for h in hs if h['kind']=='gpu' and offset+w['begin']/1000<=h['epoch']<=offset+w['end']/1000]
  units={k:max(h.get('units',{}).get(k,0) for h in cpu) for h in cpu for k in h.get('units',{})}
  summary[name]={'frames':len(selected),'gpu_ms_p50':pct('gpu_ms',.5),'cpu_thread_ms_p95':pct('cpu_thread_ms',.95),'wall_ms_p95':pct('cpu_ms',.95),'foreign_cpu_mean':statistics.mean(h['cpu_foreign'] for h in cpu) if cpu else None,'foreign_units_peak':units,'foreign_gpu_peak':max((h.get('foreign_sm') or 0 for h in gpu),default=0)}
 result[run]={'done':next((e for e in es if e['type']=='done'),None),'max_impact':max((e.get('n',0) for e in es if e['type']=='impact'),default=0),'meta':json.loads((root/run/'meta.json').read_text()),'windows':summary}
Path(__file__).with_name('perf-raw-summary.json').write_text(json.dumps(result,indent=2)+'\n')
for run,d in result.items():
 print(run,'done',d['done']['steps_done'] if d['done'] else None,'impacts',d['max_impact'])
 for name,w in d['windows'].items():
  print(name,'gpu50',round(w['gpu_ms_p50'],3),'cpu95',round(w['cpu_thread_ms_p95'],3),'foreignCPU',round(w['foreign_cpu_mean'],3),'foreignGPU',w['foreign_gpu_peak'])
