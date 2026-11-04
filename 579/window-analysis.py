"""Derived contract statistics for the retained private observer; no gameplay-latency verdict."""
import json,math,statistics
from pathlib import Path
D=Path('/mnt/data/projects/incoming/mod/run/wt-MT-L2-BUDGET/mod/run/p2-window-pair/runs')
def stats(values):
    a=sorted(values)
    if not a:return {'n':0}
    return {'n':len(a),'p50':a[math.ceil(len(a)*.5)-1],'p99':a[math.ceil(len(a)*.99)-1],
            'max':a[-1],'mean':statistics.fmean(a),'sum':sum(a)}
results={}
for arm in ['a1','b','a2']:
    g=D/arm
    es=[json.loads(l) for l in (g/'stand-out/events.jsonl').read_text().splitlines()]
    ss=json.loads((g/'summary.json').read_text());q=json.loads((g/'quality.json').read_text())
    assert not ss['issues'] and not q['contract_issues'] and q['native_counts']=={'incoming-drone':100}
    assert sum(e['type']=='blast' for e in es)==100
    ticks={e['n']:e for e in es if e['type']=='tick'}
    costs={e['n']:e for e in es if e['type']=='impact-cost'}
    windows=[e for e in es if e['type']=='work-window']
    assert windows and all(e['n'] in ticks and not e['tick_link_mismatch'] for e in windows)
    assert {e['phase'] for e in windows}=={'early','late'}
    assert all(e['account_window_seen']==(arm=='b') for e in windows)
    budgets={e['common_budget_ns'] for e in windows if e['common_budget_ns'] is not None}
    assert budgets=={30_000_000},budgets
    by_tick={}
    for e in windows:by_tick.setdefault(e['n'],[]).append(e)
    for n,rows in by_tick.items():
        ordered=sorted(rows,key=lambda r:r['observer_begin_ns'])
        assert all(a['observer_end_ns']<=b['observer_begin_ns'] for a,b in zip(ordered,ordered[1:])),n
    out={'head':('f8d6b1408b9f44cd255cb16db7253d73c7bef9b5' if arm=='b' else 'eee272309e1c8306952f32b5f6b7ed4192be2e7c'),
         'scope':'Instrumented queue windows; does not prove a universal deadline, every admission, or clean gameplay latency.',
         'common_budget_ns':30_000_000,'windows':{}}
    for name,w in ss['windows'].items():
        ns={n for n,t in ticks.items() if w['start_ms']<=t['t_ms']<w['end_ms']}
        rs=[e for e in windows if e['n'] in ns]
        if not rs:continue
        per=[]
        for n in sorted(ns):
            rows=by_tick.get(n,[])
            wall=sum(e['elapsed_wall_ns'] for e in rows)/1e6
            charged=sum(e['charged_delta_ns'] for e in rows)/1e6
            record=sum(e['record_charged_ns'] for e in rows)/1e6
            per.append({'n':n,'queue_wall_ms':wall,'queue_charged_ms':charged,'record_ms':record,
                        'outer_impact_ms':costs.get(n,{}).get('total_ms',0),
                        'window_count':len(rows),'record_count':sum(e['record_count'] for e in rows)})
        active=[r for r in per if r['window_count']]
        phase={}
        for p in ['early','late']:
            ps=[e for e in rs if e['phase']==p]
            phase[p]={'count':len(ps),'wall_ms':stats([e['elapsed_wall_ns']/1e6 for e in ps]),
                      'charged_ms':stats([e['charged_delta_ns']/1e6 for e in ps]),
                      'observer_wall_minus_charge_ms':stats([(e['elapsed_wall_ns']-e['charged_delta_ns'])/1e6 for e in ps]),
                      'record_ms':stats([e['record_charged_ns']/1e6 for e in ps])}
        out['windows'][name]={'ticks':len(ns),'active_queue_ticks':len(active),'observed_windows':len(rs),
            'queue_wall_ms':stats([r['queue_wall_ms'] for r in per]),
            'queue_charge_ms':stats([r['queue_charged_ms'] for r in per]),
            'active_queue_wall_ms':stats([r['queue_wall_ms'] for r in active]),
            'active_queue_charge_ms':stats([r['queue_charged_ms'] for r in active]),
            'record_ms':stats([r['record_ms'] for r in per]),
            'observer_wall_minus_charge_ms':stats([r['queue_wall_ms']-r['queue_charged_ms'] for r in active]),
            'actual_account_added_ms':stats([(e['account_used_after_ns']-e['account_used_before_ns'])/1e6 for e in rs if e['account_window_seen']]),
            'queue_wall_above_common':sum(r['queue_wall_ms']>30 for r in per),
            'queue_charge_above_common':sum(r['queue_charged_ms']>30 for r in per),
            'last_record_ms':stats([e['last_record_ns']/1e6 for e in rs if e['last_record_ns'] is not None]),
            'first_record_flags':sum(e['first_record_count'] for e in rs),
            'late_record_flags':sum(e['late_record_count'] for e in rs),
            'phase':phase,'top_wall_ticks':sorted(active,key=lambda r:r['queue_wall_ms'],reverse=True)[:8]}
    (g/'window-summary.json').write_text(json.dumps(out,indent=2)+'\n');results[arm]=out
    key=next(k for k in out['windows'] if k.endswith('/total'))
    r=out['windows'][key]
    print(arm,'windows',r['observed_windows'],'active ticks',r['active_queue_ticks'],
          'wall',r['active_queue_wall_ms'],'charge',r['active_queue_charge_ms'],
          'unreconciled',r['observer_wall_minus_charge_ms'])
(D.parent/'window-comparison.json').write_text(json.dumps(results,indent=2)+'\n')
