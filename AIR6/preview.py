"""Contact sheets from native frames; crop/resize/labels only. Raw inputs stay untouched."""
from pathlib import Path
import json, subprocess, os
root=Path('mod/run/sandbox/fx/out')
out=Path('mod/run/air6/previews');out.mkdir(exist_ok=True)
os.environ['MAGICK_THREAD_LIMIT']='1'
def sheet(name, items, columns, crop=True):
 args=['montage','-background','#14202c','-fill','white','-font','DejaVu-Sans','-pointsize','15']
 for scene,t,label in items:
  data=json.loads((root/scene/'capture.json').read_text())
  f=next(f for f in data['frames'] if abs(f['t']-t)<0.01)
  args+=['(',str(root/scene/f['file'])]
  if crop: args+=['-crop','960x1080+800+70','+repage','-resize','384x432!']
  else: args+=['-resize','640x353!']
  args+=['-set','label',f'{label} | actual {f["nuclearAge"]:.3f}s',')']
 args+=['-tile',f'{columns}x','-geometry','+5+5','-title','Native game frames; '+('central crop resized for comparison' if crop else 'full frame resized for comparison'),str(out/name)]
 subprocess.run(args,check=True)
items=[]
for kt in (5,15):
 for mode in ('air','ground'):
  for t in (15,60,120):items.append((f'air6-game-{kt}-{mode}',t,f'{kt}kt {mode.upper()} GAME'))
sheet('game-chronology.png',items,3)
items=[(f'air6-iris-15-{mode}',t,f'15kt {mode.upper()} Iris GAME') for mode in ('air','ground') for t in (15,30,60)]
sheet('iris-comparison.png',items,3)
sheet('visibility-controls.png',[(f'air6-game-15-air',15,'8km/300m MEDIUM'),('air6-off-15',3,'8km/300m OFF'),('air6-player60-MEDIUM',3,'4km/1.62m MEDIUM'),('air6-player60-OFF',3,'4km/1.62m OFF')],2,False)
print('3 previews completed; raw PNG unchanged')
