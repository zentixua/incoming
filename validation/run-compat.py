#!/usr/bin/env python3
"""Run one already prepared fresh copy through the retained PERFA contracts."""
import sys,json,time,subprocess
from pathlib import Path
sys.path.insert(0,'/mnt/data/projects/incoming/mod/run/wt-PERFA/tools/stand')
import client
D=Path(__file__).resolve().parent
client.ROOT=D.parents[2]
client.SOCKET='wayland-incoming-perf-c2'
client.QUIET_WAIT_S=600
game=D/'runs'/sys.argv[1]
assert not (game/'exit.json').exists(), 'Refusing retained run overwrite'
plan=json.loads((game/'stand-plan.json').read_text())
start=time.time()
with (game/'console.log').open('w') as log:
 code=client.run(game,game/'launch.sh','2560x1600',plan['limit_s'],log,lambda *s: print(*s,flush=True),quiet=False)
(game/'exit.json').write_text(json.dumps({'code':code,'start_epoch':start,'end_epoch':time.time()}))
with (game/'journal.log').open('w') as log:
 subprocess.run(['journalctl','--user','--since','@'+str(int(start)),'-o','cat'],stdout=log,stderr=subprocess.DEVNULL)
print('PERF-C2',game.name,'exit',code,flush=True)
raise SystemExit(code)
