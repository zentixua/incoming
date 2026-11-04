from pathlib import Path
import json,datetime
root=Path('/mnt/data/projects/incoming/mod/run/stand/out/mt-b5-final-dba2');out=[]
for role in ['A1','B','A2']:
 r=root/role/'complementary';ev=[json.loads(x) for x in (r/'events.jsonl').read_text().splitlines()]
 anchor=next(x for x in ev if x['type']=='srv');offset=anchor['epoch_ms']/1000-anchor['t_ms']/1000
 win=[x for x in ev if x['type']=='window' and x['name']=='100/inside'];a,b=[offset+x['t_ms']/1000 for x in win]
 samples=[json.loads(x) for x in (r/'host.jsonl').read_text().splitlines()]
 samples=[x for x in samples if x['kind']=='cpu' and a<=x['epoch']<=b]
 out.append({'role':role,'window_utc':[datetime.datetime.fromtimestamp(x,datetime.timezone.utc).isoformat() for x in (a,b)],'samples':samples})
(Path(__file__).resolve().parent/'evidence'/'bench-cpu-overlap.json').write_text(json.dumps(out,indent=2)+'\n')
