from pathlib import Path
import subprocess,json
root=Path(__file__).resolve().parent
out=root/'publish/r2';out.mkdir(parents=True,exist_ok=True)
def encode(tree,folder,label):
 p=root/tree/folder;c=json.loads((p/'capture.json').read_text())
 frames=[f for f in c['frames'] if f['age']<=2.05]
 concat=p/'r2-motion.ffconcat'; lines=['ffconcat version 1.0']
 for i,f in enumerate(frames):
  duration=frames[i+1]['age']-f['age'] if i+1<len(frames) else 1/60
  lines += [f"file '{p/f['file']}'",'option framerate 1000',f'duration {max(.001,duration):.6f}']
 concat.write_text('\n'.join(lines)+'\n')
 target=out/f'{label}.mp4'
 subprocess.run(['ffmpeg','-hide_banner','-loglevel','warning','-y','-f','concat','-safe','0','-i',str(concat),'-ss',str(frames[0]['age']),'-i',str(p/f'{c["name"]}.wav'),'-r','60','-c:v','libx264','-threads','2','-preset','fast','-crf','18','-pix_fmt','yuv420p','-c:a','aac','-b:a','160k','-shortest','-movflags','+faststart',str(target)],check=True)
 return target
for folder in ['d20','d50','d100','wet50']:
 encode('r2',folder,f'wv-{folder}')
main=encode('r2-main','d50','main-50')
subprocess.run(['ffmpeg','-hide_banner','-loglevel','warning','-y','-i',str(main),'-i',str(out/'wv-d50.mp4'),'-filter_complex','[0:v][1:v]hstack=shortest=1,drawtext=text=main:x=12:y=12:fontsize=28:fontcolor=white:box=1:boxcolor=black@0.7,drawtext=text=WV_R2:x=1292:y=12:fontsize=28:fontcolor=white:box=1:boxcolor=black@0.7[v]','-map','[v]','-an','-c:v','libx264','-threads','2','-preset','fast','-crf','18','-pix_fmt','yuv420p','-movflags','+faststart',str(out/'main-wv-50.mp4')],check=True)
subprocess.run(['ffmpeg','-hide_banner','-loglevel','warning','-y','-i',str(out/'wv-d50.mp4'),'-vf','trim=duration=0.3,setpts=4*(PTS-STARTPTS),drawtext=text=0.25x_slow_motion:x=12:y=12:fontsize=28:fontcolor=white:box=1:boxcolor=black@0.7','-an','-r','60','-c:v','libx264','-threads','2','-preset','fast','-crf','18','-pix_fmt','yuv420p','-movflags','+faststart',str(out/'front-50-slow.mp4')],check=True)
print('encoded',out)
