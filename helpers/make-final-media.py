from pathlib import Path
from PIL import Image,ImageDraw,ImageFont
import json,shutil,re,gzip,subprocess
root=Path(__file__).resolve().parents[3]
out=root/'mod/run/sm-r2/media-final'
out.mkdir(exist_ok=True)
main=root.parent/'wt-SM-main'
cache=root/'mod/run/sm-codex/media'
scenes={'raid':[20,40,55],'drone':[3,10,30],'fire':[10,20], 'early-missile-day':[.05,.1,.2,.5,1,2], 'early-missile-night':[.05,.1,.2,.5,1,2]}
if (root/'mod/run/sandbox/fx/out/sm37-final-hi-raid/capture.json').exists() and (main/'mod/run/sandbox/fx/out/sm5-main-hi-raid/capture.json').exists():
 scenes['hi-raid']=[20,40,55]
font=ImageFont.truetype('/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf',18)
def all_logs(tree):
 p=tree/'mod/run/sandbox/logs'
 return '\n'.join([*(gzip.open(f,'rt').read() for f in sorted(p.glob('*.log.gz'))),(p/'latest.log').read_text()])
newlog=all_logs(root); mainlog=all_logs(main)
metrics={}; copies=[]
for scene,times in scenes.items():
 versions=[('main',main/'mod/run/sandbox/fx/out'/f'sm5-main-{scene}',f'sm5-main-{scene}',mainlog)]
 if scene=='raid': versions.append(('review1',cache/'pr'/scene,f'sm-{scene}',(cache/'pr-shots.log').read_text()))
 versions.append(('revised',root/'mod/run/sandbox/fx/out'/f'sm37-final-{scene}',f'sm37-final-{scene}',newlog))
 sheet=Image.new('RGB',(640*len(times),380*len(versions)),'#151515'); draw=ImageDraw.Draw(sheet)
 for row,(version,src,prefix,log) in enumerate(versions):
  dst=out/version/scene; dst.mkdir(parents=True,exist_ok=True)
  manifest=json.loads((src/'capture.json').read_text())
  for p in src.iterdir():
   if p.suffix in ('.png','.wav','.json'): shutil.copy2(p,dst/p.name)
  metrics.setdefault(version,{})[scene]={'capture':manifest,'frames':{}}
  for col,t in enumerate(times):
   filename=f'{prefix}_{t:06.2f}s.png'; im=Image.open(src/filename).convert('RGB')
   line=next(l for l in reversed(log.splitlines()) if f'SANDBOX shot {filename}' in l)
   item={'raw_log':line,'pixels':im.size}
   patterns={'gpu_mean_ms':r'GPU ([\d.]+) ms','gpu_max_ms':r'GPU [\d.]+ ms \(longest ([\d.]+)',
     'wall_mean_ms':r'quads \d+, ([\d.]+) ms','clean_frame_cpu_ms':r'CPU ([\d.]+)','fps':r' fps (\d+)',
     'quads':r'quads (\d+)','estimated_native_puff_screens':r'smoke: (?:native )?fill ([\d.]+) screens',
     'raster_screens':r'puff raster ([\d.]+)/','native_bounds_screens':r'puff raster .*?native ([\d.]+)',
     'step':r'puff raster .*?step (\d+)',
     'groups':r'puff raster .*?(\d+) groups','composite_screens':r'puff raster .*?composite ([\d.]+)'}
   for key,pat in patterns.items():
    m=re.search(pat,line)
    if m:item[key]=float(m[1])
   metrics[version][scene]['frames'][str(t)]=item
   copies.append(line)
   im.thumbnail((640,346),Image.Resampling.LANCZOS)
   sheet.paste(im,(col*640,row*380+34))
   label=f'{version} | {scene} | {t}s'
   if version=='review1':label+=' | inherited wind'
   draw.text((col*640+8,row*380+6),label,font=font,fill='white')
  metrics[version][scene]['source_root']=str(src)
 sheet.save(out/f'{scene}.jpg',quality=95)
(out/'metrics.json').write_text(json.dumps(metrics,indent=2)+'\n')
(out/'shots.log').write_text('\n'.join(copies)+'\n')
head=subprocess.check_output(['git','rev-parse','HEAD'],cwd=root,text=True).strip()
reference=subprocess.check_output(['git','rev-parse','HEAD'],cwd=main,text=True).strip()
(out/'revisions.json').write_text(json.dumps({'revised':head,'main_reference':reference,'revised_base':'df4ee0d6','main_reference_base':'dc36fcba','review1':'3c7208bb'},indent=2)+'\n')
(out/'README.md').write_text(f'''# PR 428, revised smoke raster and optical density\n\nCode {head}; main reference {reference} (dc36fcba plus the same wind and presentation-clock fixtures only). Raw game PNGs and WAVs are copied unchanged. Sheets are viewing aids.\n\nRaid: main / rejected review 1 / revised. Main and revised pin the same game time (6000), sun, smoke presentation clock (0 at effect start) and camera; the inherited review-1 footage did not pin its wind clock and is a secondary reference.\n\nDrone and fire: main / revised, same camera, seed/effect and wind clock. The inherited review-1 drone/fire used a different camera and are intentionally not placed in these pairs. Capture manifests give exact age and settings.\n\nVirtual window 1280x720; actual framebuffer dimensions are in metrics.json. Power readouts are recorded in power-state.txt; no shaderpack; production Iris comparison belongs to the separate shared look gate. GPU figures include the entire FX pass, compositing and colour-stage copies. Raster totals include all sprite particles at the common resolution; composite fill and the two full-frame copies are additional work, not hidden in that counter.\n\nOld CPU estimated native puff fill and new exact projected raster bounds are different measurements. Do not compare their raw counts as if both came from the same counter. Clean-frame CPU is thread CPU time for the longest non-GC FX frame in the last second, not an average.\n''')
print(json.dumps({v:{s:d['frames'] for s,d in cases.items()} for v,cases in metrics.items()},indent=2))
