from pathlib import Path
import os,subprocess,shutil,xml.etree.ElementTree as ET
root=Path.cwd();out=root/'mod/run/air8/review-fix';p=root/'mod/src/main/java/ua/zentix/incoming/nuclear/model/StemDust.java';original=p.read_text()
os.environ['JAVA_TOOL_OPTIONS']='-XX:ActiveProcessorCount=4 -Xmx2g'
cmd=['./gradlew','--no-daemon','test','--max-workers=2','--tests','ua.zentix.incoming.nuclear.model.StemClockTest.backgroundFailureBetweenStatusAndResultStaysOutOfTheFrame']
try:
 p.write_text(original.replace('!entry.isDone() || entry.isCompletedExceptionally()', 'entry.isCompletedExceptionally()'))
 with (out/'failure-race-red.log').open('w') as log:r=subprocess.run(cmd,cwd=root/'mod',stdout=log,stderr=subprocess.STDOUT)
 xml=root/'mod/build/test-results/test/TEST-ua.zentix.incoming.nuclear.model.StemClockTest.xml';shutil.copy2(xml,out/'failure-race-red.xml')
 data=ET.parse(xml).getroot();print('failure race control',r.returncode,data.attrib,flush=True)
 assert r.returncode != 0 and int(data.get('failures'))==1 and 'CompletionException' in xml.read_text()
finally:p.write_text(original)
