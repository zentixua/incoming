from pathlib import Path
import gzip,shutil,json
r=Path('/mnt/data/projects/incoming/mod/run/wt-PERF/mod/run/perf-work');e=r/'evidence'
b=Path('/mnt/data/projects/incoming/mod/run/stand/out/perf-559-final');g=r/'runtime/data/runs/inc-100-fixed'
def gz(src,dst):
 dst.parent.mkdir(parents=True,exist_ok=True)
 with src.open('rb') as i,gzip.open(dst,'wb',compresslevel=6) as o:shutil.copyfileobj(i,o)
old=e/'runtime/frames.csv'
if old.exists():old.rename(r/'runtime-frames-retained.csv')
gz(g/'stand-out/frames.csv',e/'runtime/frames.csv.gz')
gz(g/'async.jfr.cpu.collapsed',e/'runtime/async.jfr.cpu.collapsed.gz')
a=Path('/mnt/data/projects/incoming/mod/run/perf-audit/data/runs/inc-100-async')
gz(a/'async.jfr.cpu.collapsed',e/'baseline/async.jfr.cpu.collapsed.gz')
for name in ('async-cpu-parts.json','profile-window.json'):shutil.copy2(a/name,e/'baseline'/name)
(e/'bench').mkdir(exist_ok=True)
for name in ('report.md','comment.md','look.json','pair.json','verdict.json','sheet-none.png','sheet-complementary.png','sheet-photon.png'):
 shutil.copy2(b/name,e/'bench'/name)
for run in ('A1','B','A2'):
 d=e/'bench'/run;d.mkdir(exist_ok=True)
 shutil.copy2(b/run/'results.json',d/'results.json')
 for pack in ('none','complementary','photon'):
  src=b/run/pack;dst=d/pack;dst.mkdir(exist_ok=True)
  for name in ('events.jsonl','host.jsonl'):
   if (src/name).exists():shutil.copy2(src/name,dst/name)
  gz(src/'frames.csv',dst/'frames.csv.gz')
  if run in ('B','A2'):
   (dst/'shots').mkdir(exist_ok=True)
   shutil.copy2(src/'shots/100_outside.png',dst/'shots/100_outside.png')
shutil.copy2(r/'REPORT.md',e/'REPORT.md')
shutil.copy2(r/'ci.log',e/'logs/ci.log')
shutil.copy2(r/'stand-final.log',e/'logs/stand-final.log')
shutil.copy2(Path(__file__),e/'scripts/collect_evidence.py')
print('Evidence files',sum(p.is_file() for p in e.rglob('*')),'MB',sum(p.stat().st_size for p in e.rglob('*') if p.is_file())/1e6)
