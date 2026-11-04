from pathlib import Path
import shutil,json,hashlib,subprocess,re
root=Path(__file__).resolve().parent
out=root/'evidence';out.mkdir(exist_ok=True)
for name in ('privacy-replay.log','ledger3.log','cursors.log','fire-grid.log','carriers2.log','carriers3.log','carriers4.log','many.log','review-repro2.log','review-repro3.log','review-fix.log','review.md','review-final.md','final-build.log','pr-ci.log','pipeline-final.log','scene-bench.log','pipeline-accounted.log','cache-repro.log','cache-fix.log','pr-ci-final.log','merge-memory.log','pr-ci-merged.log','pr-ci-accepted.log','pr-ci-final-head.log','ci-gametest2.log','scene-bench-isolated.log','archive-isolated.log','install_probe.py','install_media.py'):
 p=root/name
 if p.exists():shutil.copy2(p,out/name)
for label in ('main','head'):
 tree=Path('/mnt/data/projects/incoming/mod/run/wt-MT-B5-media-'+label)
 shutil.copytree(tree/'mod/run/sandbox/fx/out',out/'frames'/label,dirs_exist_ok=True)
 shutil.copytree(tree/'mod/run/sandbox/logs',out/'sandbox-logs'/label,dirs_exist_ok=True)
shutil.copy2('/mnt/data/projects/incoming/mod/run/wt-MT-B5-probe/mod/src/devtest/java/ua/zentix/incoming/nuclear/world/PipelineBench.java',out/'PipelineBench.java')
for name in ('RuinMatter','RuinSupport'):
 shutil.copy2('/mnt/data/projects/incoming/mod/run/wt-MT-B5-probe/mod/src/main/java/ua/zentix/incoming/nuclear/world/'+name+'.java',out/(name+'.instrumented.java'))
repo=root.parents[2]
ident={}
for label,ref in [('main','ab2741fa1'),('merged-main','1aca1654a'),('frames-head','381bd8c57'),('bench-head','dba2d153d'),('pre-merge-head','733c4b93c'),('merge-head','e4338e65d'),('validation-head','490a2aa84')]:
 paths=subprocess.check_output(['git','-C',str(repo),'ls-tree','-r','--name-only',ref,'mod/src/main']).decode().splitlines()
 ident[label]={'commit':subprocess.check_output(['git','-C',str(repo),'rev-parse',ref]).decode().strip(),'files':{p:hashlib.sha256(subprocess.check_output(['git','-C',str(repo),'show',ref+':'+p])).hexdigest() for p in paths}}
(out/'source-identity.json').write_text(json.dumps(ident,indent=2)+'\n')
rows={}
for line in (root/'pipeline-final.log').read_text().splitlines():
 if not any(x in line for x in ('MTPIPE sample','MTINCLUDED sample','MTEND sample','MTOWN sample')):continue
 values=dict(re.findall(r'(\w+)=([\w.\-]+)',line));n=int(values['sample']);rows.setdefault(n,{}).update(values)
(out/'pipeline-final.json').write_text(json.dumps(rows,indent=2)+'\n')
for n,r in rows.items():
 if int(r.get('kind',0))==1:
  prep=sum(float(r[k]) for k in ['snapshotMs','blastMs','collapseMs','finishMs'])
  print(n,r['kt'],'prep',round(prep,2),'apply+support',r['applyPlusSupportMs'],'elapsed',r['elapsedMs'],'ledger',r['retained'],'graph',r['graphPeak'],'heap',r['heapUsed'],'save',r['checkpointMs'])
