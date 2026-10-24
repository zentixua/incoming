# /// script
# dependencies = ["numpy", "scipy", "soundfile", "matplotlib"]
# ///
import io,json,re,subprocess
from pathlib import Path
import numpy as np
import soundfile as sf
from scipy.signal import butter,sosfilt,resample_poly
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
root=Path('/mnt/data/projects/incoming/mod/run/wt-SD')
out=Path(__file__).parent
rows={}
fig,axes=plt.subplots(3,1,figsize=(11,10))
for ax,scene in zip(axes[:2],['drone-day-50','launch-side']):
    for v in ['3.4.1','main']:
        f=root/f'docs/media/sd/rv2-{scene}-{v}.wav'
        x,rate=sf.read(f,always_2d=True)
        band=sosfilt(butter(6,[30,3000],btype='band',fs=rate,output='sos'),x,axis=0)
        n=round(.5*rate); k=len(band)//n
        db=10*np.log10((band[:k*n].reshape(k,n,2)**2).mean(axis=(1,2))+1e-30)
        time=(np.arange(k)+.5)*.5-1
        ax.plot(time,db,label=v,marker='.',lw=1)
        rows[f'{scene}/{v}']={'sample_peak_dbfs':float(20*np.log10(np.abs(x).max()+1e-30)),
            'band_rms_dbfs':db.tolist(),'window_s':.5,'first_window_start_relative_to_event_s':-1,'normalization':'none'}
    ax.set_title(f'RV2 {scene}: 30–3000 Hz, unnormalized stereo power mean, 0.5 s windows')
    ax.set_ylim(-85,0);ax.grid(alpha=.3);ax.legend();ax.set_ylabel('Band RMS (dBFS)')
raw=subprocess.check_output(['git','show','92669f5:mod/src/main/resources/assets/incoming/sounds/blast_fire.ogg'],cwd=root)
fire,rate=sf.read(io.BytesIO(raw)); ff=resample_poly(fire,10,11)
clip,rr=sf.read(root/'docs/media/sd/rv2-drone-day-50-3.4.1.wav',always_2d=True)
t=np.arange(len(clip))/rr
fit=np.interp((t-1.451)*rate,np.arange(len(ff)),ff,left=0,right=0)*.23641012295569985
fit=sosfilt(butter(6,[30,3000],btype='band',fs=rr,output='sos'),fit)
n=round(.5*rr);k=len(fit)//n
db=10*np.log10((fit[:k*n].reshape(k,n)**2).mean(axis=1)+1e-30)
axes[0].plot((np.arange(k)+.5)*.5-1,db,linestyle='--',label='old fire waveform: matched time, fitted gain (diagnostic)')
axes[0].legend(fontsize=8)
render={}
for m in re.finditer(r'RENDER_BIN v(\d+) sec(\d+) powerSum ([\d.-]+)',(out/'model.txt').read_text()):
    v,sec,value=int(m[1]),int(m[2]),float(m[3]);render.setdefault(v,{})[sec]=value
data=np.array([[render[v][sec] for sec in range(12)] for v in sorted(render)])
axes[2].fill_between(np.arange(12)+.5,np.maximum(data.min(axis=0),-140),np.maximum(data.max(axis=0),-140),alpha=.3,label='six far-grain variants; summed sector powers')
axes[2].plot(np.arange(12)+.5,np.maximum(data.mean(axis=0),-140),marker='.',lw=1)
axes[2].set_title('CPU echo rendering, saved RV2 terrain, normal threshold; excludes OpenAL, EFX and hearing filters')
axes[2].set_ylim(-140,-10);axes[2].grid(alpha=.3);axes[2].legend(fontsize=8);axes[2].set_ylabel('Model power sum (dBFS)')
for ax in axes:ax.set_xlabel('Seconds from blast / launch cue')
fig.tight_layout();fig.savefig(out/'rv2-tail-audit.png',dpi=140)
(out/'rv2-profiles.json').write_text(json.dumps(rows,indent=2)+'\n')
print(json.dumps({'echo_power_sum_1s_windows_dbfs':{str(sec):[float(data[:,sec].min()),float(data[:,sec].max())] for sec in range(12)}},indent=2))
