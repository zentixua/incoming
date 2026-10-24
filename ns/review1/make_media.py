from pathlib import Path
from PIL import Image, ImageDraw
import json, shutil, wave, re, statistics, subprocess
root=Path('/mnt/data/projects/incoming/mod/run')
base=root/'wt-NS-main';pr=root/'wt-NS';out=root/'ns-codex-media/ns/review1';out.mkdir(parents=True,exist_ok=True)
sha=lambda p:subprocess.check_output(['git','-C',str(p),'rev-parse','HEAD'],text=True).strip()
meta={'main':sha(base),'pr':sha(pr),'views':{},'performance':{},'measurement':'Quiet dev sandbox, 1280x692 image, pinned half-resolution / 28 steps; logged GPU FX layer one-second averages, not full-pack FPS.','audio':'Unmodified 48 kHz stereo PCM excerpts, seconds 45–55; no gain or resampling.'}
views={'top':('ns_r1_base_top','ns_r1_top_v2'), 'eye':('ns_r1_base_eye','ns_r1_eye'), 'wide':('ns_r1_base_wide','ns_r1_wide')}
logs={label:(tree/'mod/run/sandbox/logs/latest.log').read_text() for label,tree in [('main',base),('pr',pr)]}
def pair(a,b,labela,labelb):
 c=Image.new('RGB',(a.width+b.width,max(a.height,b.height)+24));c.paste(a,(0,24));c.paste(b,(a.width,24));d=ImageDraw.Draw(c);d.text((6,6),labela,fill='white');d.text((a.width+6,6),labelb,fill='white');return c
for view,(na,nb) in views.items():
 dirs=[t/'mod/run/sandbox/fx/out'/n for t,n in [(base,na),(pr,nb)]]
 caps=[json.loads((d/'capture.json').read_text()) for d in dirs]
 for key in ['effect','site','camera']: assert caps[0][key]==caps[1][key],(view,key)
 assert len(caps[0]['frames'])==len(caps[1]['frames'])
 delta=max(abs(a['age']-b['age']) for a,b in zip(caps[0]['frames'],caps[1]['frames']));assert delta<.12
 meta['views'][view]={'camera':caps[0]['camera'],'site':caps[0]['site'],'max_age_difference_s':delta,'frames':[f['t'] for f in caps[0]['frames']]}
 for label,cap,d,n in zip(['main','pr'],caps,dirs,[na,nb]):
  shutil.copy2(d/'capture.json',out/f'{label}_{view}_capture.json')
  with wave.open(str(d/f'{n}.wav'),'rb') as w:
   params=w.getparams();w.setpos(45*w.getframerate());data=w.readframes(10*w.getframerate())
  with wave.open(str(out/f'{label}_{view}_045-055.wav'),'wb') as w:w.setparams(params);w.writeframes(data)
  # regex groups: file suffix then GPU milliseconds
  values=[float(m[2]) for m in re.finditer(r'SANDBOX shot '+re.escape(n)+r'_([^\s]+).*?GPU ([\d.]+) ms',logs[label])]
  meta['performance'].setdefault(view,{})[label]={'median_gpu_ms':statistics.median(values),'samples_gpu_ms':values}
 for t in [45,60]+([120] if view=='wide' else []):
  files=[next(f['file'] for f in cap['frames'] if f['t']==t) for cap in caps]
  imgs=[Image.open(d/f).convert('RGB') for d,f in zip(dirs,files)]
  pair(*imgs,f'main {meta["main"][:8]} / {t}s',f'PR {meta["pr"][:8]} / {t}s').save(out/f'{view}_{t:03}s.jpg',quality=94,subsampling=0)
 if view=='top':
  frames=[]
  for a,b in zip(caps[0]['frames'],caps[1]['frames']):
   if not 45<=a['t']<=55:continue
   crop=(330,170,950,600)
   imgs=[Image.open(d/f['file']).convert('RGB').crop(crop) for d,f in zip(dirs,[a,b])]
   frames.append(pair(*imgs,f'main / {a["age"]:.2f}s',f'PR / {b["age"]:.2f}s'))
  atlas=Image.new('RGB',(frames[0].width,frames[0].height*len(frames)))
  for i,f in enumerate(frames):atlas.paste(f,(0,i*f.height))
  palette=atlas.quantize(colors=256)
  frames=[f.quantize(palette=palette,dither=Image.Dither.NONE) for f in frames]
  frames[0].save(out/'top_045-055.gif',save_all=True,append_images=frames[1:],duration=500,loop=0,optimize=False,disposal=2)
  sheet=Image.new('RGB',(620*3,430+24))
  for i,t in enumerate([45,50,55]):
   f=next(f for f in caps[1]['frames'] if f['t']==t);im=Image.open(dirs[1]/f['file']).crop((330,170,950,600));sheet.paste(im,(620*i,24));ImageDraw.Draw(sheet).text((620*i+5,5),f'PR / {t}s',fill='white')
  sheet.save(out/'motion_contact.jpg',quality=94)
(out/'comparison.json').write_text(json.dumps(meta,indent=2)+'\n')
shutil.copy2(__file__,out/'make_media.py')
print(json.dumps(meta,indent=2))
