"""Frozen copied-pack inputs for the final dust allocation comparison."""
import sys,json,shutil,hashlib
from pathlib import Path
sys.path.insert(0,'/mnt/data/projects/incoming/mod/run/perf-audit/data/scripts')
import audit
D=Path(__file__).resolve().parent;audit.RUN=D
name,arm=sys.argv[1:];assert arm in ['main','candidate']
frozen=D/'runs/final-post-b';pair=json.loads((D/'dust-pair/pair.json').read_text());jar=D/'dust-pair'/arm/'incoming-3.10.4.jar'
plan=frozen/'stand-plan.json'
sys.argv=['audit.py',name,'--plan',str(plan),'--jar',str(jar),'--modpack',str(frozen),'--production','--pack','complementary','--prepare-only','--exclude-mod','candidate-*.jar','--exclude-mod','stand-*.jar','--prop','essential.autoUpdate=false']
audit.main();g=D/'runs'/name
shutil.copytree(frozen/'essential',g/'essential',dirs_exist_ok=True)
shutil.copytree(frozen/'.cache/connector',g/'.cache/connector',dirs_exist_ok=True)
for p in(frozen/'shaderpacks').glob('*.zip.txt'):shutil.copy2(p,g/'shaderpacks'/p.name)
m=g/'manifest.json';o=json.loads(m.read_text());o['main']=pair['base']if arm=='main'else pair['candidate'];o['actual_arm']=arm;o['runner_source']='ae5aaf609';o['pair_provenance']=pair;o['copied_pack_source']='Immutable successful final-post-b, original Incoming/stand excluded and benchmark Incoming + retained stand injected';o['connector_cache_provenance']='Validated disposable final-post-b cache; containment for known cold transform race, not fresh-install proof';o['actual_plan_sha256']=hashlib.sha256((g/'stand-plan.json').read_bytes()).hexdigest();o['config_sha256']={str(p.relative_to(g)):hashlib.sha256(p.read_bytes()).hexdigest()for folder in ['config','shaderpacks']for p in(g/folder).rglob('*')if p.is_file()};m.write_text(json.dumps(o,indent=2))
assert sum('incoming' in p.name for p in(g/'mods').glob('*.jar'))==1
print(name,arm,len(list((g/'mods').glob('*.jar'))),'mods',flush=True)
