"""Quality checks and workload counters from actual telemetry; no estimates from configured salvo sizes."""
import collections,datetime,json,re,statistics,sys
from pathlib import Path
from analyze import stats
for arg in sys.argv[1:]:
 game=Path(arg);events=[json.loads(l) for l in (game/'stand-out/events.jsonl').read_text().splitlines()]
 summary=json.loads((game/'summary.json').read_text())
 origins=[e['epoch_ms']-e['t_ms'] for e in events if e['type']=='srv'];origin=statistics.median(origins)
 host=[json.loads(l) for l in (game/'stand-out/host.jsonl').read_text().splitlines()]
 gc=[]
 for line in (game/'gc.log').read_text().splitlines():
  if 'Pause' not in line:continue
  m=re.search(r'^\[([^]]+)\].*\s([\d.,]+)ms$',line)
  if m:gc.append({'end_ms':datetime.datetime.fromisoformat(m[1]).timestamp()*1000-origin,'duration_ms':float(m[2].replace(',','.'))})
 commands=[{'cmd':e['cmd'],'t_ms':e['t_ms'],'execution_wall_ms':e['t_ms']-e['ran_ms'],'queue_wait_ms':e['ran_ms']-e['sent_ms']} for e in events if e['type']=='cmd' and 'ran_ms' in e]
 out={'origin_epoch_ms':origin,'gc':gc,'windows':{},'native_entries':[],'screenlocker_mentions':[],'commands':commands}
 for name,w in summary['windows'].items():
  lo,hi=w['start_ms'],w['end_ms'];h=[v for v in host if v['kind']=='cpu' and lo<=v['epoch']*1000-origin<hi]
  packets=[v for v in events if v['type']=='packets' and lo<=v['t_ms']<hi]
  blocks=[v for v in events if v['type']=='block-writes' and lo<=v['t_ms']<hi]
  out['windows'][name]={'power_profiles':sorted({v['profile'] for v in h}),'foreign_cpu_cores':stats([v['cpu_foreign'] for v in h]),'gpu_clock_mhz':stats([v.get('gpu',{}).get('clock') for v in h]),'gpu_temperature_c':stats([v.get('gpu',{}).get('temp') for v in h]),'foreign_gpu_percent':stats([v.get('gpu',{}).get('foreign_mean') for v in h]),'gc_pause_ms':stats([v['duration_ms'] for v in gc if lo<=v['end_ms']<hi]),'packets_per_tick':{k:stats([v[k] for v in packets]) for k in ['c2s','s2c','incoming']},'block_writes_per_tick':{k:stats([v[k] for v in blocks]) for k in ['total','air','fire']},'block_writes_total':{k:sum(v[k] for v in blocks) for k in ['total','air','fire']} if blocks else None,'packet_counter_available':bool(packets),'block_counter_available':bool(blocks)}
 log=(game/'logs/latest.log').read_text(errors='replace')
 for m in re.finditer(r'PERFA native impact (\S+) at \(([^)]+)\) entryNs (\d+)(?: startMs ([\d.]+))?',log):out['native_entries'].append({'kind':m[1],'at':[float(x) for x in m[2].split(',')],'entry_ms':int(m[3])/1e6,'start_ms':float(m[4]) if m[4] else None})
 for name,w in summary['windows'].items():
  entries=[e for e in out['native_entries'] if e['start_ms'] is not None and w['start_ms']<=e['start_ms']<w['end_ms']]
  out['windows'][name]['native_entry_wall_ms']=stats([e['entry_ms'] for e in entries])
  out['windows'][name]['native_entry_wall_sum_ms']=sum(e['entry_ms'] for e in entries) if entries else None
 journal=game/'journal.log'
 if journal.exists():out['screenlocker_mentions']=[l for l in journal.read_text(errors='replace').splitlines() if 'kscreenlocker_greet' in l]
 out['native_counts']=dict(collections.Counter(v['kind'] for v in out['native_entries']))
 args=json.loads((game/'manifest.json').read_text())['args']
 plan=json.loads((game/'stand-plan.json').read_text())
 expected=collections.Counter()
 for step in plan['steps']:
  for command in ([step['cmd']] if step.get('do')=='cmd' else step.get('cmds',[])):
   if command.startswith('perfa impact '):expected[command.split()[2]]+=1
 out['native_expected']=dict(expected)
 checks=[]
 if dict(expected)!=out['native_counts']:checks.append('native impact entry count differs from plan')
 once={e['name']:e['value'] for e in summary['once']}
 active=once.get('net.irisshaders.iris.Iris#isPackInUseQuick')
 if active!=('false' if args.get('pack','none')=='none' else 'true'):checks.append('Iris active state differs from requested mode: '+str(active))
 if args.get('pack','none')!='none' and once.get('net.irisshaders.iris.Iris#getCurrentPackName')!='ComplementaryUnbound_r5.9.3.zip':checks.append('unexpected shader pack')
 if not summary['gl'] or any(e['width']!=2560 or e['height']!=1600 or 'RTX 4060 Laptop' not in e['renderer'] for e in summary['gl']):checks.append('unexpected renderer or resolution')
 if out['screenlocker_mentions']:checks.append('screen locker mentioned in run journal; inspect timing')
 out['contract_issues']=checks
 (game/'quality.json').write_text(json.dumps(out,indent=2));print(game.name,out['native_counts'],'issues',len(summary['issues']))
