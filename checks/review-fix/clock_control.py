from pathlib import Path
import os, subprocess, shutil, xml.etree.ElementTree as ET
root=Path.cwd(); out=root/'mod/run/air8/review-fix'; src=root/'mod/src/main/java/ua/zentix/incoming'
paths=[src/'nuclear/model/StemDust.java',src/'client/nuclear/MushroomField.java']
originals=[p.read_text() for p in paths]
for p,s in zip(paths,originals): (out/(p.name+'.green')).write_text(s)
cmd=['./gradlew','--no-daemon','test','--max-workers=2','--tests','ua.zentix.incoming.nuclear.model.StemClockTest','--tests','ua.zentix.incoming.client.nuclear.StemSourceTest']
os.environ['JAVA_TOOL_OPTIONS']='-XX:ActiveProcessorCount=4 -Xmx2g'
try:
 s=originals[0].replace('onset[i] = source.arrival[i] + col[i].stemFrom();','onset[i] = HobBlast.arrivalSeconds(r, hob, yieldKt) + col[i].stemFrom();').replace('double age = t - source.arrival[i];','double age = t - HobBlast.arrivalSeconds(source.radius(i), hob, yieldKt);')
 paths[0].write_text(s)
 paths[1].write_text(originals[1].replace('pall.stemSource.arrivalSeconds(wall)', 'ua.zentix.incoming.nuclear.model.HobBlast.arrivalSeconds(wall, hob, y)'))
 with (out/'wrong-clock-red.log').open('w') as log: result=subprocess.run(cmd,cwd=root/'mod',stdout=log,stderr=subprocess.STDOUT)
 xmls=list((root/'mod/build/test-results/test').glob('TEST-*Stem*Test.xml'))
 dest=out/'wrong-clock-red-xml'; dest.mkdir(exist_ok=True)
 for x in xmls: shutil.copy2(x,dest/x.name)
 failed={case.attrib['name'] for x in xmls for case in ET.parse(x).getroot().findall('testcase') if case.find('failure') is not None}
 print('wrong-clock exit',result.returncode,'failed',sorted(failed),flush=True)
 assert result.returncode != 0 and any('actualGameAndReal' in n for n in failed) and any('footUses' in n for n in failed), 'control did not distinguish the clock defect'
finally:
 for p,s in zip(paths,originals): p.write_text(s)
with (out/'correct-clock-green.log').open('w') as log: result=subprocess.run(cmd,cwd=root/'mod',stdout=log,stderr=subprocess.STDOUT)
dest=out/'correct-clock-green-xml'; dest.mkdir(exist_ok=True)
for x in (root/'mod/build/test-results/test').glob('TEST-*Stem*Test.xml'): shutil.copy2(x,dest/x.name)
print('correct-clock exit',result.returncode,flush=True)
raise SystemExit(result.returncode)
