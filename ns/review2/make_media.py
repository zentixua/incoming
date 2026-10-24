"""One-off PR #427 evidence pack; inputs are captures from tools/sandbox.sh, not a new runtime tool."""
from pathlib import Path
from PIL import Image, ImageDraw
import json, shutil, wave, re, statistics, subprocess
root=Path('/mnt/data/projects/incoming/mod/run'); pr=root/'wt-NS'; main=root/'wt-NS-main'; out=root/'ns-codex-media/ns/review2';out.mkdir(parents=True,exist_ok=True)
sha=lambda p:subprocess.check_output(['git','-C',str(p),'rev-parse','HEAD'],text=True).strip()
meta={'main':sha(main),'pr':sha(pr),'measurement':'Quiet dev sandbox. GPU FX-layer one-second averages, fixed 1/2 resolution × 28 steps. Main A / PR / independent main B, paired by requested effect age. No full-pack/Iris claim.','views':{}}
logdir=pr/'mod/run/ns-r2-logs'
def pair(a,b,left,right):
 assert a.size==b.size
 c=Image.new('RGB',(a.width*2,a.height+24));c.paste(a,(0,24));c.paste(b,(a.width,24));d=ImageDraw.Draw(c);d.text((6,6),left,fill='white');d.text((a.width+6,6),right,fill='white');return c
for res,expected in [('l',(1280,692)),('h',(2560,1600))]:
 for view in ['wide','eye','top']:
  caps={};dirs={};timings={}
  for mode,tree in [('mainA',main),('pr',pr),('mainB',main)]:
   name=f'ns_r2_{mode}_{res}_{view}';
   if mode=='pr' and res=='l' and view=='wide':name='ns_r2_pr_l_wide_direct'
   d=tree/'mod/run/sandbox/fx/out'/name;dirs[mode]=d;caps[mode]=json.loads((d/'capture.json').read_text())
   log=(logdir/f'{mode}_{res}.log').read_text();rows=list(re.finditer(r'SANDBOX shot '+re.escape(name)+r'_([\d.]+)s\.png age ([\d.]+) s fps (\d+);.*?GPU ([\d.]+) ms',log))
   timings[mode]={float(m[1]):{'gpu_ms':float(m[4]),'age':float(m[2]),'fps':int(m[3])} for m in rows};assert timings[mode]
   shutil.copy2(d/'capture.json',out/f'{mode}_{res}_{view}_capture.json')
  for key in ['effect','site','camera']: assert caps['mainA'][key]==caps['pr'][key],(res,view,key)
  assert caps['mainB']['camera']==caps['pr']['camera']
  assert caps['mainB']['site']==caps['pr']['site']
  common=sorted(set(timings['mainA'])&set(timings['pr'])&set(timings['mainB']))
  ratios=[timings['pr'][t]['gpu_ms']/statistics.mean(timings[m][t]['gpu_ms'] for m in ['mainA','mainB']) for t in common]
  noise=[timings['mainB'][t]['gpu_ms']/timings['mainA'][t]['gpu_ms'] for t in common]
  med={m:statistics.median(timings[m][t]['gpu_ms'] for t in common) for m in timings}
  limit=1.5 if view=='top' else 1.1;ratio=statistics.median(ratios)
  ages={m:{f['t']:f['age'] for f in c['frames']} for m,c in caps.items()}
  delta=max(abs(ages['mainA'][t]-ages['pr'][t]) for t in set(ages['mainA'])&set(ages['pr']));assert delta<.15
  meta['views'][f'{res}_{view}']={'camera':caps['pr']['camera'],'sites':{m:c['site'] for m,c in caps.items()},'age_delta_s':delta,'resolution':expected,'matched_times':common,'median_gpu_ms':med,'pr_over_mean_main':ratio,'mainB_over_mainA':statistics.median(noise),'limit':limit,'pass':ratio<=limit,'timings':timings}
  for t in [45,60]:
   ims=[Image.open(dirs[m]/next(f['file'] for f in caps[m]['frames'] if f['t']==t)).convert('RGB') for m in ['mainA','pr']]
   assert ims[0].size==expected,(res,ims[0].size)
   pair(*ims,f'main {meta["main"][:8]} / {expected[0]}x{expected[1]} / {t}s',f'PR {meta["pr"][:8]} / {expected[0]}x{expected[1]} / {t}s').save(out/f'{res}_{view}_{t:03}s.png')
  if res=='l':
   for mode in ['mainA','pr']:
    name=caps[mode]['name']
    with wave.open(str(dirs[mode]/f'{name}.wav'),'rb') as w:params=w.getparams();w.setpos(45*w.getframerate());data=w.readframes(10*w.getframerate())
    with wave.open(str(out/f'{mode}_{view}_045-055.wav'),'wb') as w:w.setparams(params);w.writeframes(data)
   if view=='top':
    frames=[]
    for f in caps['pr']['frames']:
     t=f['t']
     if not 45<=t<=55:continue
     ims=[Image.open(dirs[m]/next(f['file'] for f in caps[m]['frames'] if f['t']==t)).convert('RGB').crop((330,170,950,600)) for m in ['mainA','pr']]
     frames.append(pair(*ims,f'main / {ages["mainA"][t]:.2f}s',f'PR / {ages["pr"][t]:.2f}s'))
    atlas=Image.new('RGB',(frames[0].width,frames[0].height*len(frames)))
    for i,f in enumerate(frames):atlas.paste(f,(0,i*f.height))
    palette=atlas.quantize(colors=256);frames=[f.quantize(palette=palette,dither=Image.Dither.NONE) for f in frames]
    frames[0].save(out/'top_045-055.gif',save_all=True,append_images=frames[1:],duration=500,loop=0,optimize=False,disposal=2)
meta['pass']=all(v['pass'] for v in meta['views'].values());(out/'comparison.json').write_text(json.dumps(meta,indent=2)+'\n');shutil.copy2(__file__,out/'make_media.py')
print(json.dumps({k:{x:v[x] for x in ['median_gpu_ms','pr_over_mean_main','mainB_over_mainA','pass']} for k,v in meta['views'].items()},indent=2));print('PASS',meta['pass'])
