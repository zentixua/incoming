from pathlib import Path
import json,re,statistics,sys,collections
run=Path(sys.argv[1]); rows=[]
for line in (run/'lod-probe/events.tsv').read_text().splitlines():
    row={'kind':line.split()[0]}
    for k,v in re.findall(r'(\w+)=([^ ]+)',line):
        try: v=float(v) if '.' in v else int(v)
        except ValueError: pass
        row[k]=v
    rows.append(row)
def stats(xs):
    if not xs: return None
    a=sorted(xs)
    return {'n':len(a),'min':a[0],'mean':statistics.mean(a),'p50':a[len(a)//2],'p95':a[min(len(a)-1,int(len(a)*.95))],'max':a[-1]}
result={'envelopes':[],'visible':[r for r in rows if r['kind'].startswith('VISIBLE')],
        'replay':[r for r in rows if r['kind'] in ['REPLAY_DISPATCH','REPLAY_OVERWROTE','REPLAY_REPAIRED']],
        'failures':[r for r in rows if r['kind']=='FAILED']}
for begin in [r for r in rows if r['kind']=='BEGIN']:
    epoch=begin['epoch']; kt=begin['kt']; phase=begin['phase']
    subs=[r for r in rows if r['kind']=='SUBMIT' and r['epoch']==epoch]
    db=[r for r in rows if r['kind']=='DB_READY' and r['epoch']==epoch]
    summary=next((r for r in rows if r['kind']=='SUMMARY' and r['kt']==kt and r['phase']==phase),None)
    due=collections.Counter(r['due'] for r in subs)
    entry={'begin':begin,'summary':summary,'submit_cpu_ms':stats([r['ns']/1e6 for r in subs]),
           'submission_lateness_ticks':stats([max(0,r['now']-r['due']) for r in subs]) if phase else None,
           'database_lateness_ticks':stats([r['lateTicks'] for r in db]) if phase else None,
           'bytes':sum(r['bytes'] for r in subs),'transmitted_sections':sum(r['sections'] for r in subs),
           'changed_sections':sum(r['changed'] for r in subs),
           'peak_demand_columns_per_tick':max(due.values(),default=0) if phase else None,
           'peak_demand_columns_per_second':max((sum(due[t+d] for d in range(20)) for t in due),default=0) if phase else None}
    if summary:
        entry['submit_columns_per_wall_second']=len(subs)/max(1e-9,summary['submitSeconds'])
        entry['completion_columns_per_game_second']=len(subs)/max(1e-9,summary['ticks']/20)
    result['envelopes'].append(entry)
print(json.dumps(result,indent=2))
