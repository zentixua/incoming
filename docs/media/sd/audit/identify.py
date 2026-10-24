# /// script
# dependencies = ["numpy", "scipy", "soundfile"]
# ///
import io,json,subprocess
from pathlib import Path
import numpy as np
import soundfile as sf
from scipy.signal import butter,sosfilt,resample_poly,correlate,fftconvolve
root=Path('/mnt/data/projects/incoming/mod/run/wt-SD')
clip,sr=sf.read(root/'docs/media/sd/rv2-drone-day-50-3.4.1.wav',always_2d=True)
# Reduce processing to 8 kHz; band excludes the tinnitus tone and aliasing.
def band(x,rate):
    return resample_poly(sosfilt(butter(6,[30,3000],btype='band',fs=rate,output='sos'),x,axis=0),8000,rate,axis=0)
y=band(clip.mean(axis=1),sr)
rows=[]
for file in ['blast_fire.ogg']+[f'blast_tail_{i}.ogg' for i in range(1,6)]:
    raw=subprocess.check_output(['git','show','92669f5:mod/src/main/resources/assets/incoming/sounds/'+file],cwd=root)
    x,rate=sf.read(io.BytesIO(raw),always_2d=True)
    speeds=[1.1] if 'fire' in file else np.arange(.97,1.031,.002)
    best=None
    for pitch in speeds:
        z=band(x.mean(axis=1),rate)
        z=np.interp(np.arange(0,len(z)-1,pitch),np.arange(len(z)),z)
        a,b=5,10
        yy=y[a*8000:b*8000]
        if len(z)<len(yy): continue
        co=correlate(z,yy,mode='valid',method='fft')
        zz=fftconvolve(z*z,np.ones(len(yy)),mode='valid')
        rr=co/np.sqrt(np.maximum(zz,1e-30)*np.dot(yy,yy))
        k=int(np.argmax(abs(rr)))
        item=dict(file=file,pitch=float(pitch),correlation=float(rr[k]),start_in_clip_s=float(a-k/8000),gain=float(co[k]/zz[k]))
        if best is None or abs(item['correlation'])>abs(best['correlation']): best=item
    rows.append(best)
print(json.dumps(rows,indent=2))
