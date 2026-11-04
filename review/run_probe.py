from pathlib import Path
import subprocess
root=Path('/mnt/data/projects/incoming/mod/run/wt-OL')
out=Path(__file__).parent
jdk=Path('/home/artem/.var/app/org.prismlauncher.PrismLauncher/data/PrismLauncher/java/java-runtime-delta/bin')
extras=list(Path('/home/artem/.gradle/caches/modules-2/files-2.1/org.jetbrains/annotations').glob('*/*/*.jar'))
cp=':'.join([str(root/'mod/build/classes/java/main'),str(root/'mod/build/moddev/artifacts/neoforge-21.1.264-merged.jar'),*map(str,extras),*(root/'mod/build/moddev/clientScenarioLegacyClasspath.txt').read_text().splitlines()])
sources=[root/'mod/src/main/java/ua/zentix/incoming/client/fx/smoke/DustGrid.java',root/'mod/src/main/java/ua/zentix/incoming/client/far/SmokePuffs.java']
r=subprocess.run([str(jdk/'javac'),'-proc:none','-cp',cp,'-d',str(out),*map(str,sources),str(out/'FarDustProbe.java'),str(out/'DustProbe.java')],cwd=out,text=True,stdout=subprocess.PIPE,stderr=subprocess.STDOUT)
if r.returncode:
 print(r.stdout);raise SystemExit(r.returncode)
for main,log in [('ua.zentix.incoming.client.far.FarDustProbe','far-dust-probe.txt'),('DustProbe','dust-probe.txt')]:
 r=subprocess.run([str(jdk/'java'),'-Xmx128m','-cp',str(out)+':'+cp,main],cwd=out,text=True,stdout=subprocess.PIPE,stderr=subprocess.STDOUT)
 (out/log).write_text(r.stdout)
 print(r.stdout)
 if r.returncode:raise SystemExit(r.returncode)
