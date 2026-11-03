import csv,json,re,statistics as st
from pathlib import Path
r=Path('/mnt/data/projects/incoming/mod/run/stand/out/perf-559-final')
def avg(v):return round(st.mean(v),4) if v else None
def med(v):return round(st.median(v),4) if v else None
out={}
for run in ('A1','B','A2'):
 p=r/run/'none';ev=[json.loads(l) for l in (p/'events.jsonl').read_text().splitlines()]
 w=[e for e in ev if e['type']=='window' and e['name']=='100/inside'];lo,hi=[x['t_ms'] for x in w]
 frames=[f for f in csv.DictReader((p/'frames.csv').open()) if lo<=float(f['t_ms'])<hi and f['shot']=='0']
 probes=[e for e in ev if e['type']=='probe' and lo<=e['t_ms']<hi and 'describeTiming' in e['name']]
 rows=[]
 for e in probes:
  s=e['value'];m=re.search(r'GPU by pass .*?: (.*?); drained',s)
  parts={k:float(v) for k,v in re.findall(r'(models|depth|water|pall|heat|probes|quads|bloom) ([\d.]+)',m[1])}
  q=re.search(r'half-resolution smoke (\d+) quads, full (\d+).*lays (\d+)',s)
  rows.append(dict(t=e['t_ms'],quad_count=int(re.match(r'quads (\d+)',s)[1]),half=int(q[1]),full=int(q[2]),lays=int(q[3]),**parts))
 origins=[e['epoch_ms']-e['t_ms'] for e in ev if e['type']=='srv']; origin=st.median(origins)
 host=[h for h in map(json.loads,(p/'host.jsonl').read_text().splitlines()) if h.get('kind')=='cpu' and lo<=h['epoch']*1000-origin<hi]
 data={'lo':lo,'hi':hi,'frames':len(frames),'gpu_p50':med([float(f['gpu_ms']) for f in frames if f['gpu_ms']]),'stage_mean':{},'probe_means':{k:avg([x[k] for x in rows]) for k in rows[0] if k!='t'},'host':host,'seconds':rows,'bins':[]}
 for stage in ('after_level+','after_solid_blocks','after_translucent_blocks','tail'):
  data['stage_mean'][stage]=avg([float(z.split('=')[1]) for f in frames for z in f['stages'].split(';') if z.startswith(stage+'=')])
 for i in range(4):
  fs=[f for f in frames if lo+i*5000<=float(f['t_ms'])<lo+(i+1)*5000]
  data['bins'].append({'seconds':i*5,'gpu_p50':med([float(f['gpu_ms']) for f in fs if f['gpu_ms']])})
 data["first_impact"]=next(e for e in ev if e["type"]=="impact")
 data["impact_count"]=sum(e["type"]=="impact" for e in ev)
 out[run]=data
 print(run, {k:v for k,v in data.items() if k not in ('host','seconds')})
 print('host',[(h['gpu'],h['cpu_foreign']) for h in host])
Path('mod/run/perf-review/paired-rows.json').write_text(json.dumps(out,indent=2))
