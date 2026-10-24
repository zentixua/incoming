from pathlib import Path
import json, shutil
from PIL import Image, ImageDraw
root=Path('mod/run/sandbox/fx/out')
media=Path('mod/run/bn-media/bn/review1')
media.mkdir(parents=True,exist_ok=True)
for day in ['night','day']:
    sheet=Image.new('RGB',(160*9,206*3),'#202020')
    draw=ImageDraw.Draw(sheet)
    for row,kind in enumerate(['main','before','fixed']):
        folder=root/f'bn-r1-{kind}-{day}'
        meta=json.loads((folder/'capture.json').read_text())
        target=media/day/kind
        target.mkdir(parents=True,exist_ok=True)
        shutil.copy2(folder/'capture.json',target/'capture.json')
        shutil.copy2(folder/(meta['name']+'.wav'),target/'audio.wav')
        for col,frame in enumerate(meta['frames']):
            file=folder/frame['file']
            shutil.copy2(file,target/frame['file'])
            im=Image.open(file).convert('RGB')
            if im.size!=(1280,692):raise ValueError(im.size)
            sheet.paste(im.crop((560,340,720,520)),(160*col,206*row+26))
            draw.text((160*col+3,206*row+4),f'{kind} {frame["age"]:.2f}s',fill='white')
    sheet.save(media/f'cleanup-{day}.jpg',quality=95)
shutil.copy2('mod/run/bn-handoff/review1-sources.json',media/'sources.json')
for file in ['capture-review1.py','package-review1.py']:
    shutil.copy2(Path('mod/run/bn-handoff')/file,media/file)
print('packaged review1')
