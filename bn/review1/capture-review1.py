from pathlib import Path
import json, subprocess, signal
signal.signal(signal.SIGTERM, lambda *_: (_ for _ in ()).throw(SystemExit(143)))
def run(*args):
    print('RUN', *args, flush=True)
    subprocess.run(args, check=True)
paths=['mod/src/main/java/ua/zentix/incoming/client/render/SpentBoosterRenderer.java',
       'mod/src/main/java/ua/zentix/incoming/entity/SpentBoosterEntity.java',
       'mod/src/main/java/ua/zentix/incoming/client/IncomingClient.java',
       'mod/src/main/java/ua/zentix/incoming/client/render/StrikeProjectileRenderer.java']
tests='mod/src/devtest/java/ua/zentix/incoming/gametest/BoosterGameTests.java'
saved={p:Path(p).read_bytes() for p in paths+[tests]}
head=subprocess.check_output(['git','rev-parse','HEAD'],text=True).strip()
base=subprocess.check_output(['git','merge-base','HEAD','origin/main'],text=True).strip()
old='ba3aa60d92efee6281823c93fc100e433bfa985a'
def capture(kind):
    run('tools/sandbox.sh','fx','track off','zoom 1')
    for day in ['night','day']:
        run('tools/sandbox.sh','shot',f'bn-r1-{kind}-{day}','spent missile 20',
            '--cam','7 4 0 0','--at','3,3.5,4,4.5,5,5.5,6,6.5,7','--time',day,'--wav','7','--no-swap')
ok=False
try:
    capture('fixed')
    for kind,ref,replace in [('before',old,paths[:2]),('main',base,paths)]:
        # The baseline entity predates the new lifecycle API; tests do not run in this visual-only client.
        Path(tests).write_text('package ua.zentix.incoming.gametest; public final class BoosterGameTests {}\n')
        for p in replace: Path(p).write_bytes(subprocess.check_output(['git','show',ref+':'+p]))
        run('tools/sandbox.sh','restart')
        capture(kind)
    Path('mod/run/bn-handoff/review1-sources.json').write_text(json.dumps({'fixed':head,'before':old,'main':base},indent=2)+'\n')
    ok=True
finally:
    for p,data in saved.items(): Path(p).write_bytes(data)
    if not ok:
        print('CAPTURE FAILED: sources restored; retain lease for immediate diagnosis. Stop/release explicitly after inspection.',flush=True)
if ok: print('CAPTURE COMPLETE: sources restored; inspect PNGs before stop/release. Client still holds main code.',flush=True)
