from pathlib import Path
import subprocess, json, shutil, sys
root=Path.cwd()
reference='--reference' in sys.argv
prefix='r2-main' if reference else 'r2'
evidence=Path(__file__).resolve().parent/prefix
evidence.mkdir(exist_ok=True)
provenance={'head':subprocess.check_output(['git','rev-parse','HEAD'],text=True).strip(),
            'base':subprocess.check_output(['git','merge-base','HEAD','origin/main'],text=True).strip(),
            'main_at_capture':subprocess.check_output(['git','rev-parse','origin/main'],text=True).strip(),
            'dirty':subprocess.check_output(['git','status','--porcelain'],text=True).strip(),
            'profile':'quiet','renderer':'dev sandbox, native GPU, no Iris'}
allowed={'mod/src/devtest/java/ua/zentix/incoming/scenario/Sandbox.java','mod/src/devtest/java/ua/zentix/incoming/scenario/SandboxCommand.java'}
dirty_files={line[3:] for line in subprocess.check_output(['git','status','--porcelain'],text=True).splitlines()}
if provenance['dirty'] and not dirty_files <= (allowed if reference else {'mod/src/devtest/java/ua/zentix/incoming/scenario/Sandbox.java'}):
    raise RuntimeError('Commit production changes before the evidence capture')
provenance['fixture']='dev-only stance/weather + VSync off / 60 FPS patch' if reference else 'tracked WV fixture + temporary VSync off / 60 FPS patch'
provenance['vsync']=False
provenance['fps_limit']=60
(evidence/'source.json').write_text(json.dumps(provenance,indent=2)+'\n')
def run(*args):
    subprocess.run(['tools/sandbox.sh',*args],check=True)
run('swap')
run('fx','stance stand','weather clear','wait 100')
run('shot',f'{prefix}-warm','blast drone 7','--at','0.1','--wav','1','--cam','50 1.62 0 3','--no-swap')
# Continuous motion for the first two seconds, then the resting pose.
times=','.join(f'{i/60:.4f}' for i in range(121))+',3'
for d in (20,50,100):
    name=f'{prefix}-d{d}'
    run('shot',name,'blast drone 7','--at',times,'--wav','4','--cam',f'{d} 1.62 0 3','--no-swap')
    shutil.copytree(root/'mod/run/sandbox/fx/out'/name,evidence/f'd{d}',dirs_exist_ok=True)
run('fx','weather rain','wait 100')
run('shot',f'{prefix}-wet-warm','blast drone 7','--at','0.1','--wav','1','--cam','50 1.62 0 3','--no-swap')
run('shot',f'{prefix}-wet50','blast drone 7','--at',times,'--wav','4','--cam','50 1.62 0 3','--no-swap')
shutil.copytree(root/'mod/run/sandbox/fx/out'/f'{prefix}-wet50',evidence/'wet50',dirs_exist_ok=True)
run('fx','weather clear','wait 100')
print('R2 captures:', evidence)
