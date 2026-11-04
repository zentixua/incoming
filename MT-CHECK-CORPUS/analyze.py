from pathlib import Path
import csv, json, math, statistics, hashlib
ROOT=Path(__file__).resolve().parent
lines=(ROOT/'paired.log').read_text().splitlines()
rows=[]
for line in lines:
 if 'CACHE_PAIR ' not in line: continue
 fields=dict(item.split('=',1) for item in line.split('CACHE_PAIR ',1)[1].split())
 for k,v in list(fields.items()):
  if v in ('true','false'): fields[k]=v=='true'
  elif k!='group': fields[k]=float(v) if '.' in v else int(v)
 rows.append(fields)
assert len(rows)==3040, ('incomplete rows',len(rows))
assert len({(r['case'],r['sample']) for r in rows})==3040
assert {r['case'] for r in rows}==set(range(760))
assert all(r['fullEqual'] for r in rows)
assert any('CACHE_CORPUS_DONE cases=760 pairs=3040 timedPairs=1520 fullEqual=true' in l for l in lines)
for case in range(760):
 r=[r for r in rows if r['case']==case]
 assert {x['sample'] for x in r}=={0,1,2,3}
 assert [x['cachedFirst'] for x in r]==[True,False,True,False]
 assert all(x['warm']==(x['sample']<2) for x in r)
for r in rows:
 for v in ('cached','uncached'):
  r[v+'TotalNs']=r[v+'SolveNs']+r[v+'FinishNs']
  r[v+'TotalBytes']=r[v+'SolveBytes']+r[v+'FinishBytes']
 r['timeRatio']=r['uncachedTotalNs']/r['cachedTotalNs']
 r['allocationRatio']=r['uncachedTotalBytes']/r['cachedTotalBytes']
with (ROOT/'pairs.csv').open('w') as f:
 w=csv.DictWriter(f,fieldnames=list(rows[0]));w.writeheader();w.writerows(rows)
timed=[r for r in rows if not r['warm']]
assert len(timed)==1520

def q(values,p):
 values=sorted(values); i=(len(values)-1)*p; lo=int(i);hi=math.ceil(i)
 return values[lo]+(values[hi]-values[lo])*(i-lo)
def dist(values):
 return {'n':len(values),'median':statistics.median(values),'p90':q(values,.9),'sum':sum(values)}
def summary(rs):
 a=[r['cachedTotalNs'] for r in rs];b=[r['uncachedTotalNs'] for r in rs]
 ba=[r['cachedTotalBytes'] for r in rs];bb=[r['uncachedTotalBytes'] for r in rs]
 result={'pairs':len(rs),'time_ratio':dist([r['timeRatio'] for r in rs]),'summed_time_ratio':sum(b)/sum(a),'cached_total_ns':dist(a),'uncached_total_ns':dist(b),
 'allocation_ratio':dist([r['allocationRatio'] for r in rs]),'summed_allocation_ratio':sum(bb)/sum(ba),'cached_total_bytes':dist(ba),'uncached_total_bytes':dist(bb),
 'gc_pairs':sum(r['gcMs']>0 for r in rs),'gc_ms':sum(r['gcMs'] for r in rs)}
 for key in ['cachedSolveNs','uncachedSolveNs','cachedFinishNs','uncachedFinishNs','cachedSolveBytes','uncachedSolveBytes','cachedFinishBytes','uncachedFinishBytes']:
  result[key]=dist([r[key] for r in rs])
 return result
case_rows=[]
for case in range(760):
 rs=[r for r in timed if r['case']==case];r=rs[0]
 case_rows.append({'case':case,'group':r['group'],'x':r['x'],'z':r['z'],'psi':r['psi'],'timed_pairs':len(rs),
 'time_ratio_median':statistics.median(x['timeRatio'] for x in rs),'summed_time_ratio':sum(x['uncachedTotalNs'] for x in rs)/sum(x['cachedTotalNs'] for x in rs),
 'cached_ns_sum':sum(x['cachedTotalNs'] for x in rs),'uncached_ns_sum':sum(x['uncachedTotalNs'] for x in rs),
 'cached_bytes_sum':sum(x['cachedTotalBytes'] for x in rs),'uncached_bytes_sum':sum(x['uncachedTotalBytes'] for x in rs),'full_equal':True})
with (ROOT/'cases.csv').open('w') as f:
 w=csv.DictWriter(f,fieldnames=list(case_rows[0]));w.writeheader();w.writerows(case_rows)
setup=[r for r in rows if r['sample']==0]
out={'cases':760,'pairs':3040,'timed_pairs':1520,'full_equal_pairs':3040,'overall':summary(timed),
 'case_time_ratio_medians':dist([r['time_ratio_median'] for r in case_rows]),
 'cold_nine_blast_setup_ns':dist([r['coldBlastSetupNs'] for r in setup]),'cold_nine_blast_setup_bytes':dist([r['coldBlastSetupBytes'] for r in setup]),
 'parse_ns':dist([r['parseNs'] for r in setup]),'prime_ns':dist([r['primeNs'] for r in setup]),'snapshot_ns':dist([r['snapshotNs'] for r in setup]),
 'encode_restore_both_ns':dist([r['encodeRestoreNs'] for r in timed]),
 'by_pressure':{str(p):summary([r for r in timed if r['psi']==p]) for p in sorted({r['psi'] for r in timed})},
 'by_group':{g:summary([r for r in timed if r['group']==g]) for g in sorted({r['group'] for r in timed})},
 'by_order':{str(a):summary([r for r in timed if r['cachedFirst']==a]) for a in [True,False]},
 'by_gc':{str(a):summary([r for r in timed if (r['gcMs']>0)==a]) for a in [True,False] if any((r['gcMs']>0)==a for r in timed)}}
(ROOT/'summary.json').write_text(json.dumps(out,indent=2)+'\n')
(ROOT/'cache-numeric.log').write_text('\n'.join(l.split('[ua.ze.in.Incoming/]: ',1)[-1] if '[ua.ze.in.Incoming/]: ' in l else l for l in lines if any(x in l for x in ['CACHE_PAIR ','CACHE_PROGRESS ','CACHE_CORPUS_','CACHE_PROVIDER ']))+'\n')
print(json.dumps({'cases':out['cases'],'timed_pairs':out['timed_pairs'],'full_equal_pairs':out['full_equal_pairs'],'overall':out['overall'],'cold_nine_blast_setup_ns':out['cold_nine_blast_setup_ns']}))
