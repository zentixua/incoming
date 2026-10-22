# /// script
# dependencies = ["numpy","scipy"]
# ///
"""Мгновенная громкость (BS.1770, окно 400 мс) и уровни по отрезкам: 3.4.1 против main без оглушения."""
import subprocess, sys, numpy as np
from scipy.signal import lfilter
from pathlib import Path
R=48000
def load(p):
    raw=subprocess.run(["ffmpeg","-v","error","-i",str(p),"-f","f32le","-ac","2","-ar",str(R),"-"],capture_output=True).stdout
    return np.frombuffer(raw,np.float32).reshape(-1,2)
B1=[1.53512485958697,-2.69169618940638,1.19839281085285];A1=[1,-1.69065929318241,0.73248077421585]
B2=[1,-2,1];A2=[1,-1.99004745483398,0.99007225036621]
def kpow(x):
    y=lfilter(B2,A2,lfilter(B1,A1,x,axis=0),axis=0); return (y**2).sum(axis=1)
def momentary(x):
    p=kpow(x); w=int(.4*R); h=R//10; c=np.concatenate([[0],np.cumsum(p)])
    return np.array([-0.691+10*np.log10((c[i+w]-c[i])/w+1e-15) for i in range(0,len(p)-w,h)])
def seg(x,a,b):
    p=kpow(x)[int(a*R):int(b*R)]; return -0.691+10*np.log10(p.mean()+1e-15)
root=Path(sys.argv[1]); keys=sys.argv[2:] or ["3.4.1","main-hearing-off"]
for d in sorted(root.iterdir()):
    if not d.is_dir() or d.name=="work": continue
    for k in keys:
        p=d/f"{k}.ogg"
        if not p.exists(): continue
        x=load(p); M=momentary(x); L=len(x)/R
        cuts=[(0.8,3),(3,6),(6,9),(9,12),(12,L)]
        print(f"{d.name:20s} {k[:6]:6s} Mmax {M.max():6.1f} @{M.argmax()/10+0.4:4.1f} | "+" ".join(f"{a:.0f}-{b:.0f}:{seg(x,a,b):6.1f}" for a,b in cuts if b>a))
