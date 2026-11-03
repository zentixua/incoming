"""Frame-level hitch evidence: interval, render bracket, GPU-clock span and nearby events."""
import csv,json,sys
from pathlib import Path
for path in sys.argv[1:]:
 g=Path(path);f=list(csv.DictReader((g/'stand-out/frames.csv').open()));ev=[json.loads(l) for l in (g/'stand-out/events.jsonl').read_text().splitlines()]
 windows={};spans=[]
 for e in ev:
  if e['type']=='window':
   if e['edge']=='begin':windows[e['name']]=e['t_ms']
   elif e['name'] in windows:spans.append((windows[e['name']],e['t_ms'],e['name']))
 rows=[]
 for i,r in enumerate(f[:-1]):
  n=f[i+1];t=float(r['t_ms']);dt=float(n['prev_interval_ms'] or 0)
  if dt<=33.333 or int(r['shot']):continue
  w=[name for lo,hi,name in spans if lo<=t<hi]
  if not w:continue
  rows.append({'window':w[0],'frame':r['frame'],'start_ms':t,'interval_ms':dt,'render_wall_ms':float(r['cpu_ms']),'render_thread_cpu_ms':float(r['cpu_thread_ms']),'cycle_thread_cpu_ms':float(n['prev_cpu_cycle_ms']) if n.get('prev_cpu_cycle_ms') else None,'gpu_elapsed_ms':float(r['gpu_ms']),'events':[e for e in ev if t-100<=e['t_ms']<=t+dt+100 and e['type'] in ('cmd','impact','blast','tick') and (e['type']!='tick' or e['wall_ms']>30)]})
 (g/'freezes.json').write_text(json.dumps(rows,indent=2))
 print(g.name, 'total',len(rows))
 for r in sorted(rows,key=lambda x:-x['interval_ms'])[:10]:print({k:v for k,v in r.items() if k!='events'})
