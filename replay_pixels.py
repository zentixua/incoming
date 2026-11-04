# /// script
# dependencies = ["Pillow"]
# ///
from PIL import Image
from pathlib import Path
import sys,json,statistics
folder=Path(sys.argv[1])/'screenshots'
# Fixed pixel strip through the reinstated tower in this fixed 1280x692 replay camera.
# Neighbour strips estimate the background at the same height. Raw frames remain the authority.
def contrast(p):
 im=Image.open(p).convert('L')
 if im.size!=(1280,692): return {'file':p.name,'unsupported_size':im.size}
 vals=[]
 for y in range(349,371):
  center=statistics.mean(im.getpixel((x,y)) for x in range(637,643))
  bg=statistics.mean(im.getpixel((x,y)) for x in [631,632,633,646,647,648])
  vals.append(abs(center-bg))
 return {'file':p.name,'strip_contrast':statistics.mean(vals),'max_row_contrast':max(vals)}
ps=[folder/n for n in ['replay_before.png','replay_stale_unrepaired.png','replay_repaired_0.png','replay_repaired_1.png']]
ps+=sorted(folder.glob('replay_[0-9][0-9][0-9]_*.png'))+sorted(folder.glob('replay_[0-9]_[0-9][0-9][0-9]_*.png'))
print(json.dumps([contrast(p) for p in ps if p.exists()],indent=2))
