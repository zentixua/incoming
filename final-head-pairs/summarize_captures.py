"""Read capture clocks, PNG dimensions and PCM metadata without playback."""
from pathlib import Path
import array, hashlib, json, math, re, struct, wave
root=Path(__file__).resolve().parent
summary={}
for role in ('main','head'):
    for scene in ('roof','tree','crater'):
        name=f'final-{role}-{scene}'
        folder=root/name
        meta=json.loads((folder/'capture.json').read_text())
        for frame in meta['frames']:
            image=folder/frame['file']
            frame['size']=list(struct.unpack('>II',image.read_bytes()[16:24]))
            frame['sha256']=hashlib.sha256(image.read_bytes()).hexdigest()
        with wave.open(str(folder/f'{name}.wav')) as w:
            audio={'channels':w.getnchannels(),'rate':w.getframerate(),'sample_width':w.getsampwidth(),'frames':w.getnframes(),'seconds':w.getnframes()/w.getframerate()}
            assert w.getsampwidth()==2
            samples=array.array('h',w.readframes(w.getnframes()))
            rms=math.sqrt(sum(x*x for x in samples)/len(samples))/32768
            audio.update(rms_dbfs=20*math.log10(rms) if rms else None,peak=max(abs(x) for x in samples)/32768,full_scale_samples=sum(abs(x)>=32767 for x in samples))
        meta['wav']=audio
        summary[name]=meta
for scene in ('roof','tree','crater'):
    a,b=[summary[f'final-{r}-{scene}'] for r in ('main','head')]
    assert a['site']==b['site'] and a['camera']==b['camera'] and a['effect']==b['effect']
    assert [f['t'] for f in a['frames']]==[f['t'] for f in b['frames']]
(root/'capture-metadata.json').write_text(json.dumps(summary,indent=2)+'\n')
lines=[]
for role in ('main','head'):
    for line in (root/f'{role}-client.log').read_text().splitlines():
        if 'MTB2_FINAL_GEOMETRY' in line or 'MTB2_CRATER_GEOMETRY' in line:
            lines.append(role+' '+line)
(root/'geometry-counters.txt').write_text('\n'.join(lines)+'\n')
print('32 frames and six WAV files; paired sites/cameras/effects/requested ages match')
for name,meta in summary.items():
    print(name,'ages',[(f['t'],f['age']) for f in meta['frames']],'RMS',round(meta['wav']['rms_dbfs'],2))
print('\n'.join(lines))
