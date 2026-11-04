"""Summarize retained matched recordings; no capture or historical data mutation."""
import csv,json,sys,collections
from pathlib import Path
sys.path.insert(0,'/mnt/data/projects/incoming/mod/run/perf-audit/data/scripts')
from analyze import stats
sys.path.insert(0,'/mnt/data/projects/incoming/mod/run/wt-PERFA/tools/stand')
from results import host_in
D=Path(__file__).resolve().parent
out=[]
for name in sys.argv[1:]:
 g=D/'runs'/name;s=json.loads((g/'summary.json').read_text());q=json.loads((g/'quality.json').read_text());e=[json.loads(x)for x in(g/'stand-out/events.jsonl').read_text().splitlines()];h=[json.loads(x)for x in(g/'stand-out/host.jsonl').read_text().splitlines()];rows=list(csv.DictReader((g/'stand-out/frames.csv').open()))
 for i,r in enumerate(rows[:-1]):
  n=rows[i+1];r['interval']=float(n['prev_interval_ms']);r['cycle_cpu']=float(n['prev_cpu_cycle_ms']);r['t']=float(r['t_ms'])
 windows={}
 for k,w in s['windows'].items():
  lo,hi=w['start_ms'],w['end_ms'];f=[r for r in rows[:-1] if lo<=r['t']<hi];worst=max(f,key=lambda r:r['interval']) if f else None
  windows[k]={'frame':stats([r['interval']for r in f]),'render_cpu':stats([float(r['cpu_thread_ms'])for r in f]),'full_cycle_cpu':stats([r['cycle_cpu']for r in f]),'gpu_timestamp_span':stats([float(r['gpu_ms'])for r in f if r['gpu_ms']]),'late_ms_over50':sum(r['interval']for r in f if r['interval']>50),'host':host_in(h,e,lo,hi),'worst':{x:worst[x]for x in ['frame','t_ms','interval','cpu_ms','cpu_thread_ms','cycle_cpu']} if worst else None}
 out.append({'run':name,'source':json.loads((g/'manifest.json').read_text())['main'],'windows':windows,'workload':{'native':q['native_counts'],'blasts':sum(x['type']=='blast' for x in e)},'contract_issues':q['contract_issues'],'issues':s['issues']})
p=D/'dust-final-pair.json';p.write_text(json.dumps(out,indent=2))
for r in out:
 w=next(v for k,v in r['windows'].items()if k.endswith('/total'));print(r['run'],w['frame'],w['host'],r['workload'])
