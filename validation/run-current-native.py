"""One fresh copied-pack diagnostic; attach existing profilers only around the first batch."""
import json,time,subprocess,threading,statistics,sys
from pathlib import Path
sys.path.insert(0,'/mnt/data/projects/incoming/mod/run/wt-PERFA/tools/stand')
import client
D=Path(__file__).resolve().parent
client.ROOT=D.parents[2];client.SOCKET='wayland-incoming-perf-c2'
game=D/'runs/current-native'
assert not(game/'exit.json').exists(),'Refusing retained run overwrite'
stop=threading.Event();state={}
java=Path.home()/'.var/app/org.prismlauncher.PrismLauncher/data/PrismLauncher/java/java-runtime-delta/bin'
asprof=D/'tooling/async-profiler-4.6-linux-x64/bin/asprof'
def control():
 ap=None
 try:
  eventfile=game/'stand-out/events.jsonl'
  while not eventfile.exists():
   if stop.wait(.1):raise RuntimeError('Client ended before event file')
  baseline=None;offsets=[];pending=''
  with eventfile.open() as f:
   while baseline is None:
    if stop.is_set():raise RuntimeError('Client ended before baseline window')
    line=f.readline()
    if not line:stop.wait(.1);continue
    pending+=line
    if not pending.endswith('\n'):continue
    e=json.loads(pending);pending=''
    if e['type']=='srv':offsets.append(e['epoch_ms']-e['t_ms'])
    if e['type']=='window'and e.get('edge')=='begin'and e['name'].endswith('/baseline'):baseline=e['t_ms']
  assert offsets,'No wall/monotonic alignment before baseline'
  origin=statistics.median(offsets);target=(origin+baseline+8500)/1000
  state.update(origin_epoch_ms=origin,baseline_t_ms=baseline,requested_start_epoch=target,duration_s=5)
  if time.time()>target-.25:raise RuntimeError('Baseline trigger read too late; refuse an incomplete early capture')
  while time.time()<target:
   if stop.wait(min(.1,max(0,target-time.time()))):raise RuntimeError('Client ended before profile start')
  pid=int((game/'java.pid').read_text().strip())
  assert str(game.resolve()).encode()in Path(f'/proc/{pid}/cmdline').read_bytes(),'PID not this owned copied client'
  state.update(pid=pid,asprof_launch_epoch=time.time())
  with(game/'windowed-profiler.log').open('w')as log:
   ap=subprocess.Popen([str(asprof),'-d','5','-e','cpu','-i','2ms','-t','--cstack','dwarf','-o','jfr','-f',str(game/'async.jfr'),str(pid)],stdout=log,stderr=subprocess.STDOUT)
   state['jfr_command_epoch']=time.time()
   j=subprocess.run([str(java/'jcmd'),str(pid),'JFR.start','name=perfc2','settings=profile','duration=5s','filename='+str(game/'profile.jfr')],stdout=log,stderr=subprocess.STDOUT,timeout=15)
   state['jfr_return_epoch']=time.time();state['jfr_start_returncode']=j.returncode
   state['asprof_returncode']=ap.wait(timeout=20)
  state['controller_end_epoch']=time.time()
  assert j.returncode==0 and ap.returncode==0,'Profiler attach or collection failed'
  assert(game/'async.jfr').stat().st_size>1000,'Missing native recording'
  state['status']='collected'
 except BaseException as ex:
  state.update(status='failed',error=repr(ex))
  if ap is not None and ap.poll()is None:
   ap.terminate()
   try:ap.wait(timeout=5)
   except subprocess.TimeoutExpired:ap.kill();ap.wait()
 finally:(game/'native-capture.json').write_text(json.dumps(state,indent=2))
t=threading.Thread(target=control,name='PERF-C2 profile window',daemon=True);t.start();start=time.time()
try:
 with(game/'console.log').open('w')as log:
  code=client.run(game,game/'launch.sh','2560x1600',240,log,lambda*s:print(*s,flush=True),quiet=False)
finally:stop.set();t.join(timeout=25)
(game/'exit.json').write_text(json.dumps({'code':code,'start_epoch':start,'end_epoch':time.time(),'profiling':state},indent=2))
with(game/'journal.log').open('w')as log:subprocess.run(['journalctl','--user','--since','@'+str(int(start)),'-o','cat'],stdout=log,stderr=subprocess.DEVNULL)
assert not t.is_alive(),'Profile controller did not finish'
assert state.get('status')=='collected',state
assert(game/'profile.jfr').stat().st_size>1000,'Missing stockJFR recording'
print('PERF-C2 current-native exit',code,flush=True)
raise SystemExit(code)
