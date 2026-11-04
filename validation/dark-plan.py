import json,sys
from pathlib import Path
game=Path(sys.argv[1]);p=game/'stand-plan.json';q=json.loads(p.read_text())
def c(cmd):return {'do':'cmd','cmd':cmd}
def w(s):return {'do':'wait','s':s}
def shot(name):return {'do':'shot','name':name}
steps=[{'do':'tp','eye':[-18,77,28],'look':[6,72,6],'settle_s':5},c('time set 6000'),c('weather clear'),c('forceload add -48 -48 48 48'),w(5),c('fill 0 72 0 12 100 12 stone_bricks hollow'),w(3),c('sable assemble area 0 72 0 12 100 12'),w(5),shot('dark-assembled'),{'do':'tp','eye':[6.5,80.5,6.5],'look':[6.5,79,1],'settle_s':3}]
for i in range(24):
 steps += [c('particle minecraft:block{block_state:{Name:"minecraft:stone"}} 6.5 80.5 6.5 0.2 0.1 0.2 0 24 force'),c('particle minecraft:block{block_state:{Name:"minecraft:stone"}} 20.5 80.5 6.5 0.2 0.1 0.2 0 24 force'),w(.2)]
 if i in (2,7,14,23):steps.append(shot('dark-interior-'+str(i)))
steps += [{'do':'tp','eye':[-18,77,28],'look':[6,72,6],'settle_s':3},c('weather rain'),{'do':'window','name':'dark-compat/rain','s':12},shot('dark-rain'),c('weather clear'),c('forceload remove all'),w(3)]
q['steps']=steps;q['perfa_no_shots']=False;q['probes'] += ['ua.zentix.stand.LightProbe#read']
q['probes'] += ['neoforge.fun.qu_an.minecraft.asyncparticles.client.core.Diagnostic#'+m for m in ['isTemporaryGpuOnlyAsyncParticleTick','isTemporaryDisableAsyncParticleTick','isTemporaryDisableAnimationTick','isTemporaryDisableAsyncRainTick']]
p.write_text(json.dumps(q,indent=2))
