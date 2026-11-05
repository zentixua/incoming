# /// script
# dependencies = ["Pillow"]
# ///
from pathlib import Path
import json
from PIL import Image,ImageDraw,ImageFont
root=Path('/mnt/data/projects/incoming/mod/run/wt-AIR8-evidence')
frames=json.loads((root/'native/frames.json').read_text())
font=ImageFont.truetype('/usr/share/fonts/TTF/DejaVuSans.ttf',20)
out=root/'native/previews';out.mkdir(exist_ok=True)
def frame(version,scene,t):
 r=next(x for x in frames if x['version']==version and x['scene']==scene and x['t']==t)
 return r, root/'native'/version/'raw'/('air8-'+version+'-'+scene)/r['file']
def sheet(scene,ages,crop=None):
 width=900; height=round(width*(crop[3]-crop[1])/(crop[2]-crop[0])) if crop else round(width*1412/2560)
 canvas=Image.new('RGB',(2*width,len(ages)*(height+48)),(18,20,25));draw=ImageDraw.Draw(canvas)
 for row,t in enumerate(ages):
  for col,v in enumerate(['before','after']):
   meta,p=frame(v,scene,t)
   with Image.open(p) as src:
    im=src.convert('RGB')
    if crop: im=im.crop(crop)
    im=im.resize((width,height),Image.Resampling.LANCZOS)
   x=col*width;y=row*(height+48);canvas.paste(im,(x,y+48))
   draw.text((x+12,y+12),f'{v.upper()} | {scene} | actual age {meta["nuclearAge"]:.6f} s',font=font,fill='white')
 suffix='-lower-crop' if crop else '-full'
 canvas.save(out/(scene+suffix+'.png'))
for scene in ['5-air','5-ground','15-air','15-ground']:sheet(scene,[15,30,60])
sheet('high',[50,60,75]);sheet('high',[50,60,75],(500,950,2060,1412))
(out/'README.md').write_text('Comparison previews are labeled resizes of native frames. The high lower-field crop uses identical native coordinates (500,950)-(2060,1412) in both versions. No recoloring, enhancement or synthetic content. Originals, exact ages and camera settings remain in ../before/raw and ../after/raw. The high control uses OFF pall/LOW mushroom in both versions; the post-wave skirt remains independently visible. Native framing partly crops the lower skirt, equally in both versions. These are controlled flat-scene comparisons, not calibrated real-world images.\n')
print('wrote',len(list(out.glob('*.png'))),'preview sheets')
