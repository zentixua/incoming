#!/usr/bin/env python3
"""Reuse retained PERFA runner without mutating its checkout or historical evidence."""
import sys,json,shutil,hashlib,re
from pathlib import Path
OLD=Path('/mnt/data/projects/incoming/mod/run/perf-audit/data')
sys.path.insert(0,str(OLD/'scripts'))
import audit
D=Path(__file__).resolve().parent
AUDIT_ROOT=audit.ROOT
audit.RUN=D
def main():
 name=sys.argv[1]
 candidate='-ap-' in name
 plan=json.loads((OLD/'runs/inc-city-asyncparticles-2/stand-plan.json').read_text())
 plan['limit_s']=600
 plan['probes']+=['ua.zentix.stand.AuditProbes#particles','ua.zentix.stand.AuditProbes#particleClasses']
 if candidate:plan['probes'] += ['neoforge.fun.qu_an.minecraft.asyncparticles.client.core.Diagnostic#'+x for x in ['isTemporaryGpuOnlyAsyncParticleTick','isTemporaryDisableAsyncParticleTick','isTemporaryDisableAnimationTick','isTemporaryDisableAsyncRainTick']]
 dest=D/(name+'-plan.json');dest.write_text(json.dumps(plan,indent=2))
 args=['audit.py',name,'--plan',str(dest),'--jar',str(D/'candidate-f69381911.jar'),'--modpack','host','--production','--pack','complementary','--prepare-only','--prop','essential.autoUpdate=false']
 if candidate:args+=['--extra-mod',str(D/'AsyncParticles-21.1.4.4+1.21.1.jar')]
 sys.argv=args
 audit.main()
 game=D/'runs'/name
 src=audit.MC/'essential'
 if src.exists():shutil.copytree(src,game/'essential',dirs_exist_ok=True)
 for rel in ['essential/essential-loader.properties','essential/loader/stage1/modlauncher9/stage2.forge_1.17.1.properties']:
  p=game/rel;p.parent.mkdir(parents=True,exist_ok=True)
  text=p.read_text() if p.exists() else ''
  (p.with_name(p.name+'.before-perf-c2')).write_text(text)
  text=re.sub(r'^autoUpdate=.*$', 'autoUpdate=false',text,flags=re.M)
  if not re.search(r'^autoUpdate=',text,re.M):text+='\nautoUpdate=false\n'
  p.write_text(text)
 manifest=json.loads((game/'manifest.json').read_text());manifest['main']='f69381911602154c0ce994f57150881169a4cfe9';manifest['runner_source']='ae5aaf609';manifest['candidate_source']=None
 (game/'manifest.json').write_text(json.dumps(manifest,indent=2))
 shutil.copytree(D/'runs/dark-main/.cache/connector',game/'.cache/connector',dirs_exist_ok=True)
 manifest['connector_cache_provenance']='Identical validated disposable dark-main Connector cache; cold transform race containment, not fresh-install validation'
 (game/'manifest.json').write_text(json.dumps(manifest,indent=2))
 print(name,'prepared',len(list((game/'mods').glob('*.jar'))),'mods')
if __name__=='__main__':main()
