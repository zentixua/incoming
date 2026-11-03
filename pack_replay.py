from pathlib import Path
import json,subprocess
root=Path(__file__).resolve().parent
results=[]
def hashes(args):
    data=subprocess.check_output(['ffmpeg','-v','error',*args,'-pix_fmt','rgb24','-f','framemd5','-'],text=True)
    return [l.rsplit(',',1)[1].strip() for l in data.splitlines() if not l.startswith('#')]
for run in sorted(root.glob('client-*')):
    if not run.is_dir() or not (run/'screenshots').exists(): continue
    pattern=str(run/'screenshots/replay_[0-9][0-9][0-9]_*.png')
    out=run/'replay-lossless.mkv'
    subprocess.run(['ffmpeg','-v','error','-n','-framerate','90','-pattern_type','glob','-i',pattern,
                    '-c:v','libx264rgb','-crf','0','-preset','veryfast','-threads','2','-pix_fmt','rgb24',str(out)],check=True)
    before=hashes(['-framerate','90','-pattern_type','glob','-i',pattern])
    after=hashes(['-i',str(out)])
    assert before==after and len(before)==90,(run,len(before),len(after))
    results.append({'run':run.name,'frames':len(before),'verified_rgb_frame_md5':before,'video_bytes':out.stat().st_size})
(root/'replay-video-verification.json').write_text(json.dumps(results,indent=2)+'\n')
print(json.dumps([{k:v for k,v in r.items() if k!='verified_rgb_frame_md5'} for r in results],indent=2))
