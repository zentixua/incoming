"""Profiler export after a client exits; run within laptop_job, never alongside a measured client."""
import json,os,subprocess,sys
from pathlib import Path
D=Path('/mnt/data/projects/incoming/mod/run/perf-audit/data');S=D/'scripts'
java=Path.home()/'.var/app/org.prismlauncher.PrismLauncher/data/PrismLauncher/java/java-runtime-delta'
env=dict(os.environ,JAVA_HOME=str(java))
for arg in sys.argv[1:]:
 game=Path(arg)
 summary=json.loads((game/'summary.json').read_text());quality=json.loads((game/'quality.json').read_text())
 measured=[v for k,v in summary['windows'].items() if not k.endswith('/baseline')]
 lo=round(quality['origin_epoch_ms']+min(v['start_ms'] for v in measured));hi=round(quality['origin_epoch_ms']+max(v['end_ms'] for v in measured))
 (game/'profile-window.json').write_text(json.dumps({'from_epoch_ms':lo,'to_epoch_ms':hi}))
 for profile in game.rglob('*.sparkprofile'):
  with Path(str(profile)+'.analysis.log').open('w') as log:
   subprocess.run(['uv','run',str(S/'spark_parts.py'),str(profile)],stdout=log,stderr=subprocess.STDOUT,env=env,check=True)
 for name in ['profile.jfr','async.jfr']:
  profile=game/name
  if not profile.exists():continue
  conv=D/'tooling/async-profiler-4.6-linux-x64/bin/jfrconv'
  for kind in ['cpu','alloc'] if name=='profile.jfr' else ['cpu']:
   with (game/(name+'.'+kind+'.log')).open('w') as log:
    subprocess.run([str(conv),'--'+kind,'--threads','--from',str(lo),'--to',str(hi),str(profile),str(game/(name+'.'+kind+'.html'))],stdout=log,stderr=subprocess.STDOUT,env=env,check=True)
  with (game/(name+'.collapsed.log')).open('w') as log:
   subprocess.run([str(conv),'--cpu','--threads','--dot','--from',str(lo),'--to',str(hi),'-o','collapsed',str(profile),str(game/(name+'.cpu.collapsed'))],stdout=log,stderr=subprocess.STDOUT,env=env,check=True)
  if name=='profile.jfr':
   with (game/'jfr-analysis.log').open('w') as log:
    subprocess.run([str(java/'bin/java'),str(S/'JfrAgg.java'),str(profile),str(game/'jfr')],stdout=log,stderr=subprocess.STDOUT,env=env,check=True)

 if (game/'profile.jfr').exists():
  subprocess.run([sys.executable,str(S/'jfr_hitches.py'),str(game)],check=True,env=env)
  subprocess.run([sys.executable,str(S/'jfr_server_hitches.py'),str(game)],check=True,env=env)

