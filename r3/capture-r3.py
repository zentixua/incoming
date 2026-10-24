# Съёмка кадров PR #425, круг 3: тот же стенд, что r2 (шахед 50 кг, зерно 7, стоя, 20/50/100 м, дождь 50 м).
# Запуск из корня дерева wt-WV, песочница уже запущена. Кадры — mod/run/wv-r3/r3/<сцена>/.
from pathlib import Path
import subprocess, json, shutil, sys
root = Path.cwd()
evidence = Path(__file__).resolve().parent / 'r3'
evidence.mkdir(exist_ok=True)
dirty = {line[3:] for line in subprocess.check_output(['git', 'status', '--porcelain'], text=True).splitlines() if not line.startswith('??')}
if not dirty <= {'mod/src/devtest/java/ua/zentix/incoming/scenario/Sandbox.java'}:
    raise RuntimeError(f'commit production changes before the capture: {dirty}')
(evidence / 'source.json').write_text(json.dumps({
    'head': subprocess.check_output(['git', 'rev-parse', 'HEAD'], text=True).strip(),
    'main_at_capture': subprocess.check_output(['git', 'rev-parse', 'origin/main'], text=True).strip(),
    'dirty': sorted(dirty), 'profile': 'quiet', 'renderer': 'dev sandbox, native GPU, no Iris',
    'fixture': 'tracked WV stance/weather + temporary VSync off / 60 FPS patch (capture-settings.patch)'}, indent=2) + '\n')
def run(*args):
    subprocess.run(['tools/sandbox.sh', *args], check=True)
only = sys.argv[1:] or ['d20', 'd50', 'd100', 'wet50']
run('swap')
run('fx', 'stance stand', 'weather clear', 'wait 100')
run('shot', 'r3-warm', 'blast drone 7', '--at', '0.1', '--wav', '1', '--cam', '50 1.62 0 3', '--no-swap')
times = ','.join(f'{i/60:.4f}' for i in range(121)) + ',3,5'
for d in (20, 50, 100):
    if f'd{d}' not in only: continue
    name = f'r3-d{d}'
    run('shot', name, 'blast drone 7', '--at', times, '--wav', '4', '--cam', f'{d} 1.62 0 3', '--no-swap')
    shutil.copytree(root / 'mod/run/sandbox/fx/out' / name, evidence / f'd{d}', dirs_exist_ok=True)
if 'wet50' in only:
    run('fx', 'weather rain', 'wait 100')
    run('shot', 'r3-wet-warm', 'blast drone 7', '--at', '0.1', '--wav', '1', '--cam', '50 1.62 0 3', '--no-swap')
    run('shot', 'r3-wet50', 'blast drone 7', '--at', times, '--wav', '4', '--cam', '50 1.62 0 3', '--no-swap')
    shutil.copytree(root / 'mod/run/sandbox/fx/out/r3-wet50', evidence / 'wet50', dirs_exist_ok=True)
    run('fx', 'weather clear', 'wait 100')
print('r3 captures:', evidence)
