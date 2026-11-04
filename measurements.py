from pathlib import Path
import re,json,statistics
root=Path(__file__).resolve().parent;out=root/'evidence';rows={}
for line in (root/'pipeline-accounted.log').read_text().splitlines():
 if not any(x in line for x in ('MTPIPE sample','MTINCLUDED sample','MTEND sample','MTOWN sample')):continue
 v=dict(re.findall(r'(\w+)=([\w.\-]+)',line));n=int(v['sample']);rows.setdefault(n,{}).update(v)
(out/'pipeline-accounted.json').write_text(json.dumps(rows,indent=2)+'\n')
text='''# Registered finite-chunk measurements

Final accounted probe: `pipeline-accounted.log`; exact source/instrumentation beside it. Two passes per yield/fixture; no cold reads. Values below are observations, not a full-map deadline or statistical p95 estimate.

Earlier #560: **7.68 ms/chunk apply-only**, versus **10.10 ms/chunk median including support** (7.93–39.82 ms). Changed block counts and scene history differ; do not read this as equal-work speedup/regression.

| Yield / dense sample | Changed blocks | Snapshot + blast + collapse + finish (ms) | Apply only (ms) | Apply + support (ms/chunk) | All material continuation (ms) | Largest support step (ms) |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
'''
for n,r in rows.items():
 if r['kind']!='1':continue
 prep=sum(float(r[k]) for k in ['snapshotMs','blastMs','collapseMs','finishMs'])
 text+=f"| {float(r['kt']):g} kt / {n} | {r['changed']} | {prep:.2f} | {float(r['applyMs']):.2f} | {float(r['applyPlusSupportMs']):.2f} | {float(r['allMaterialMs']):.2f} | {float(r['maxSupportUnitMs']):.4f} |\n"
text+='''
Support calls are included in material continuation; do not add both columns. The sparse controls changed zero blocks and therefore do not establish support throughput. The first dense pass has no surviving support component and records zero graph steps; it is excluded from the live-support summary.

| Yield / dense sample | Immediate pending pieces / support seeds | Peak ledger estimate (MiB) | Peak observed shared reservation (MiB) | Live support graph peak (KiB) | Heap sample at completion (MiB) | Active ledger checkpoint (ms / MiB NBT estimate) |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
'''
for n,r in rows.items():
 if r['kind']!='1':continue
 text+=f"| {float(r['kt']):g} kt / {n} | {r['pending']} / {r['support']} | {int(r['ledgerPeak'])/2**20:.2f} | {int(r['reservationPeak'])/2**20:.2f} | {int(r['graphPeak'])/1024:.2f} | {int(r['heapUsed'])/2**20:.2f} | {float(r['checkpointMs']):.2f} / {int(r['checkpointNbtBytes'])/2**20:.2f} |\n"
dense=[float(r['applyPlusSupportMs']) for r in rows.values() if r['kind']=='1' and int(r['supportCalls'])>0]
text+=f'\nAcross the five dense observations with live support graph steps, apply + support median is **{statistics.median(dense):.2f} ms/chunk**, range **{min(dense):.2f}–{max(dense):.2f} ms**.\n'
text+='''
The ledger graph is sampled inside each production support step; post-apply ownership is sampled before the next world tick. The shared counter includes the context attached to `NuclearWorld`. Heap samples include the entire GameTest JVM and are not peak or allocation-rate measurements. The 512 MiB limit concerns admitted nuclear working reservations, not JVM heap. Checkpoint timing measures NBT construction, **not durable disk flush**. Original raw timing logs include concurrent build/stand activity; these small samples cannot establish an isolated throughput ceiling.

The timed dense preparation stage is dominated here by fracture/blast and collapse solving; the cumulative material lifecycle is larger than plan apply alone. This does not establish what dominates a complete cold map.

**Complete acceptance-to-flash time at 15 / 100 / 1000 kt: not measured. The 1 Mt 10–15-minute target is not passed.** There is no real whole-map time figure to report from this probe. The earlier plan's 523,988-chunk workload and 10.10 ms sample would multiply to 88.2 single-thread CPU minutes, but that is only a sensitivity calculation: changed density, reuse, live-tick share, I/O and publication differ. It is deliberately not offered as a prediction of actual preparation time.
'''
(out/'measurements.md').write_text(text)
print(text)
