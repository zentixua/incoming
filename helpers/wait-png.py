# /// script
# dependencies = ["Pillow"]
# ///
"""The sandbox reply can precede the asynchronous PNG writer; do not stop its JVM yet."""
import json,sys,time
from pathlib import Path
from PIL import Image
root=Path(sys.argv[1]);cap=json.loads((root/'capture.json').read_text());deadline=time.monotonic()+20
for frame in cap['frames']:
 while True:
  try:
   with Image.open(root/frame['file']) as im: im.verify()
   break
  except (OSError,SyntaxError):
   if time.monotonic()>deadline: raise
   time.sleep(.1)
print(f'PNG complete: {root.name} ({len(cap["frames"])})')
