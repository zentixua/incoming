"""Exact JFR-to-frame attribution. Run between measured clients, within laptop_job."""
import collections,csv,json,subprocess,sys
from pathlib import Path
S=Path(__file__).parent
java=Path.home()/'.var/app/org.prismlauncher.PrismLauncher/data/PrismLauncher/java/java-runtime-delta/bin/java'
for arg in sys.argv[1:]:
 g=Path(arg)
 subprocess.run([sys.executable,str(S/'freezes.py'),str(g)],check=True,stdout=subprocess.DEVNULL)
 origin=json.loads((g/'quality.json').read_text())['origin_epoch_ms']
 spans=[r for r in json.loads((g/'freezes.json').read_text()) if r['interval_ms']>50]
 (g/'hitch-spans.tsv').write_text(''.join(f"{r['frame']}\t{origin+r['start_ms']}\t{origin+r['start_ms']+r['interval_ms']}\n" for r in spans))
 subprocess.run([str(java),str(S/'JfrSpans.java'),str(g/'profile.jfr'),str(g/'hitch-spans.tsv'),str(g/'hitch-events.tsv')],check=True)
 by={r['frame']:{**{k:v for k,v in r.items() if k!='events'},'samples':0,'inclusive_methods':collections.Counter(),'leaf':collections.Counter(),'duration_events':[]} for r in spans}
 for e in csv.DictReader((g/'hitch-events.tsv').open(),delimiter='\t'):
  r=by[e['span']]
  if e['event'] in ('jdk.ExecutionSample','jdk.NativeMethodSample') and e['thread'] in ('main','Render thread'):
   r['samples']+=1
   names=e['stack'].split(';');r['leaf'][names[0]]+=1
   for n in set(names):r['inclusive_methods'][n]+=1
  elif e['event'] not in ('jdk.ExecutionSample','jdk.NativeMethodSample','jdk.ObjectAllocationSample'):
   r['duration_events'].append(e)
 for r in by.values():
  r['inclusive_methods']=dict(r['inclusive_methods'].most_common(40));r['leaf']=dict(r['leaf'].most_common(20))
 (g/'hitch-attribution.json').write_text(json.dumps(list(by.values()),indent=2))
