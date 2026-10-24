# /// script
# dependencies = ["pillow"]
# ///
from pathlib import Path
from PIL import Image,ImageDraw,ImageFont
import json,shutil,subprocess
root=Path(__file__).resolve().parent
out=root/'publish/r2';out.mkdir(parents=True,exist_ok=True)
fontpath=subprocess.check_output(['fc-match','-f','%{file}','DejaVu Sans'],text=True)
font=ImageFont.truetype(fontpath,18)
def read(tree,name):
 p=root/tree/name
 return p,json.loads((p/'capture.json').read_text())
def nearest(cap,t):return min(cap['frames'],key=lambda f:abs(f['age']-t))
def sheet(name,rows,times,filename,crop=None):
 w,h=(640,346) if crop is None else (crop[2]-crop[0],crop[3]-crop[1])
 image=Image.new('RGB',(110+w*len(times),42+len(rows)*(h+36)),'#181818');d=ImageDraw.Draw(image)
 d.text((10,8),name,font=font,fill='white')
 for row,(label,tree,folder) in enumerate(rows):
  p,c=read(tree,folder); y=42+row*(h+36)
  d.text((5,y+50),label,font=font,fill='white')
  for col,t in enumerate(times):
   f=nearest(c,t); im=Image.open(p/f['file']).convert('RGB')
   if crop: im=im.crop(crop)
   im=im.resize((w,h),Image.Resampling.LANCZOS)
   image.paste(im,(110+col*w,y+32))
   d.text((114+col*w,y+4),f'actual age {f["age"]:.4f} s',font=font,fill='white')
 image.save(out/filename,quality=94)
for dist in (20,50,100):
 rows=[('main','r2-main',f'd{dist}'),('WV R2','r2',f'd{dist}')]
 sheet(f'Shahed 50 kg, {dist} m, day, standing: main / WV revision 2. Actual ages below.',rows,[.03,.06,.1],f'compare-{dist}.jpg')
 sheet(f'Shahed {dist} m: WV R2 outward front, real capture ages.', [('WV R2','r2',f'd{dist}')],[.015,.05,.085,.12],f'front-motion-{dist}.jpg')
 sheet(f'Shahed {dist} m: early front, WV R1 / WV R2. Same camera; actual ages below.', [('WV R1','wv',f'd{dist}'),('WV R2','r2',f'd{dist}')],[.025,.05,.1],f'early-{dist}.jpg')
sheet('Rain 50 m: main / WV revision 2. Condensation forms behind the shock.', [('main','r2-main','wet50'),('WV R2','r2','wet50')],[.04,.08,.15],'rain-50.jpg')
# Full-size early details, no enlargement or contrast edits.
sheet('50 m early detail: WV R1 / WV R2 (raw-pixel crop).', [('WV R1','wv','motion50'),('WV R2','r2','d50')],[.03,.06],'front-crop-50.jpg',(240,240,1040,640))
# Publish a selection of original PNGs, original unprocessed WAVs and their true ages.
for tree,folder in [(tree,folder) for tree in ('r2','r2-main') for folder in ('d20','d50','d100','wet50')]:
 p,c=read(tree,folder);dest=out/folder if tree=='r2' else out/'main'/folder;dest.mkdir(parents=True,exist_ok=True)
 times=[0,.017,.033,.05,.067,.083,.1,.15,.25,.5,1,3]
 frames=list({f['file']:f for f in [nearest(c,t) for t in times]}.values())
 clean=dict(c);clean['frames']=frames;clean['continuous_frames_total']=len(c['frames'])
 clean['audio']=dict(c['audio'],file=f'{folder}.wav')
 clean['note']='Selected original PNGs. Continuous first-two-second frames are in the movie; full local capture retained.'
 (dest/'capture.json').write_text(json.dumps(clean,indent=2)+'\n')
 (dest/'frame-ages.csv').write_text('requested_s,actual_age_s\n'+''.join(f'{f["t"]},{f["age"]}\n' for f in c['frames']))
 for f in frames:shutil.copy2(p/f['file'],dest/f['file'])
 shutil.copy2(p/f'{c["name"]}.wav',dest/f'{folder}.wav')
shutil.copy2(root/'r2/source.json',out/'source.json')
shutil.copy2(root/'r2-main/source.json',out/'main-source.json')
for filename in ['condensation.csv','CondensationNumbers.java']:
 shutil.copy2(root/filename,out/filename)
print(out)

reference=Path('/mnt/data/projects/incoming/mod/run/compare-final/drone-day-50')
p,c=read('r2','d50')
w,h=480,270;times=[.1,.5,1,3]
image=Image.new('RGB',(100+4*w,2*(h+36)+45),'#181818');d=ImageDraw.Draw(image)
d.text((8,8),'50 m: 3.4.1 Newisle/Iris archive / WV R2 sandbox; different scenery, presentation reference.',font=font,fill='white')
for row,label in enumerate(['3.4.1','WV R2']):
 y=45+row*(h+36);d.text((5,y+80),label,font=font,fill='white')
 for col,t in enumerate(times):
  f=nearest(c,t)
  file=reference/f'3.4.1_{t:g}s.jpg' if row==0 else p/f['file']
  im=Image.open(file).convert('RGB');im.thumbnail((w,h))
  image.paste(im,(100+col*w,y+32))
  text=f'archive {t:g} s' if row==0 else f'actual {f["age"]:.4f} s'
  d.text((104+col*w,y+4),text,font=font,fill='white')
image.save(out/'reference-341-50.jpg',quality=94)
