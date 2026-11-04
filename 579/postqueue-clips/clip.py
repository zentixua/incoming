import sys
sys.dont_write_bytecode=True
import os,json,subprocess,hashlib
from pathlib import Path
D=Path(__file__).resolve().parent;R=D.parent/'p2-window-pair/runs';S=Path('/mnt/data/projects/incoming/mod/run/perf-audit/data/scripts')
conv=Path('/mnt/data/projects/incoming/mod/run/perf-audit/data/tooling/async-profiler-4.6-linux-x64/bin/jfrconv');java=Path(os.environ['JAVA_HOME'])/'bin/java'
for arm,n in [('b',1681),('a2',1481)]:
 src=R/arm;out=D/arm;out.mkdir(exist_ok=False)
 events=[json.loads(l) for l in (src/'stand-out/events.jsonl').read_text().splitlines()];tick=next(e for e in events if e['type']=='tick' and e['n']==n);q=json.loads((src/'quality.json').read_text());origin=q['origin_epoch_ms']
 lo=origin+tick['t_ms']-tick['wall_ms'];hi=origin+tick['t_ms'];span=out/'span.tsv';span.write_text(f'{n}\t{lo:.6f}\t{hi:.6f}\n')
 md={'arm':arm,'tick':tick,'origin_epoch_ms':origin,'from_epoch_ms_exact':lo,'to_epoch_ms_exact':hi,'from_epoch_ms_rounded':round(lo),'to_epoch_ms_rounded':round(hi),'same_n':[e for e in events if e.get('n')==n],'input_hashes':{name:hashlib.sha256((src/name).read_bytes()).hexdigest() for name in ['profile.jfr','async.jfr','stand-out/events.jsonl','quality.json','manifest.json']}}
 (out/'clip.json').write_text(json.dumps(md,indent=2))
 subprocess.run([str(conv),'--cpu','--threads','--dot','--from',str(round(lo)),'--to',str(round(hi)),'-o','collapsed',str(src/'async.jfr'),str(out/'native.cpu.collapsed')],check=True)
 subprocess.run([str(java),str(S/'JfrSpans.java'),str(src/'profile.jfr'),str(span),str(out/'stock.tsv')],check=True)
 print('EXACT PEAK DONE',arm,n,flush=True)
