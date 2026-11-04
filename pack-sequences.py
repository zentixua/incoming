from pathlib import Path
import subprocess,json
base=Path(__file__).resolve().parent
results=[]
def hashes(args):
    output=subprocess.check_output(['ffmpeg','-v','error','-threads','2',*args,'-pix_fmt','rgb24','-f','framemd5','-'],text=True)
    return [line.rsplit(',',1)[1].strip() for line in output.splitlines() if not line.startswith('#')]
for run in sorted(base.glob('client-*')):
    if not (run/'screenshots').exists(): continue
    sequences={}
    for p in sorted((run/'screenshots').glob('*.png')):
        parts=p.stem.split('_')
        if parts[0]=='front' and len(parts)==4: key='_'.join(parts[:2])
        elif parts[0]=='replay' and len(parts)==3 and parts[1].isdigit(): key='replay'
        elif parts[0]=='replay' and len(parts)==4 and parts[1].isdigit(): key='_'.join(parts[:2])
        else: continue
        sequences.setdefault(key,[]).append(p)
    for key,frames in sequences.items():
        pattern=str(run/'screenshots'/f'{key}_[0-9][0-9][0-9]_tick*.png')
        out=run/f'{key}-lossless.mkv'
        if not out.exists():
            subprocess.run(['ffmpeg','-v','error','-n','-framerate','120','-pattern_type','glob','-i',pattern,
                            '-c:v','libx264rgb','-crf','0','-preset','veryfast','-threads','2','-pix_fmt','rgb24',str(out)],check=True)
        before=hashes(['-framerate','120','-pattern_type','glob','-i',pattern])
        after=hashes(['-i',str(out)])
        assert before==after and len(before)==len(frames),(run,key,len(before),len(after))
        results.append({'run':run.name,'sequence':key,'frames':[p.name for p in frames],
                        'rgb_frame_md5':before,'video_bytes':out.stat().st_size,
                        'timing':'120 fps preview; original frame/tick timestamps in names and events.tsv are authoritative'})
(base/'video-verification.json').write_text(json.dumps(results,indent=2)+'\n')
print(json.dumps([{k:v for k,v in r.items() if k not in ['frames','rgb_frame_md5']}|{'count':len(r['frames'])} for r in results],indent=2))
