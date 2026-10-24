from pathlib import Path
import re,subprocess
root=Path('mod/src/main/resources/assets/incoming/shaders')
def expand(p,seen):
 if p in seen:return ''
 seen.add(p)
 return re.sub(r'#moj_import\s+<incoming:([^>]+)>',lambda m:expand(root/'include'/m[1],seen),p.read_text())
paths=[]
for suffix,stage in [('vsh','vert'),('fsh','frag')]:
 p=Path('mod/run/sm-r2')/f'analytic.{stage}'
 p.write_text(expand(root/'core'/f'fx.{suffix}',set())); paths.append(str(p))
subprocess.run(['glslangValidator','-l',*paths],check=True)
