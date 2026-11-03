"""Inclusive running-CPU sample counts, separately for each thread. No wall-time conversion."""
from collections import Counter
import json,sys
from pathlib import Path
terms=['SmokePuffs','CloudModel','GroundDust','ParticleEngine','BlastSounds','BlastPulse','LocalWind','Air.of','WorkScheduler','BlastFront','RuinSettle','RadiationTicker','MobPanic']
for arg in sys.argv[1:]:
 g=Path(arg);p=g/'async.jfr.cpu.collapsed'
 if not p.exists():continue
 roots={}
 for line in p.read_text().splitlines():
  stack,n=line.rsplit(' ',1);n=int(n);root=stack.split(';',1)[0]
  counts=roots.setdefault(root,Counter());counts['total']+=n
  for term in terms:
   if term in stack:counts[term]+=n
 result={'source':p.name,'scope':'profile-window.json; running CPU samples; inclusive terms overlap and must not be added','threads':{name:dict(counts) for name,counts in sorted(roots.items(),key=lambda x:-x[1]['total'])}}
 (g/'async-cpu-parts.json').write_text(json.dumps(result,indent=2))
