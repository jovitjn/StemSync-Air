#!/usr/bin/env python3
"""Original 32-second, 120 BPM instrumental: sixteen 4/4 bars, Am–F–C–G four times.
Deterministic synthesis. No sampled recordings. All stems are mono 44.1kHz PCM16.
"""
from pathlib import Path
import numpy as np
import wave, hashlib, json
ROOT = Path(__file__).resolve().parents[1] / 'app/src/main/assets/demo'
SR = 44100
N = SR * 32
rng = np.random.default_rng(8128)
stems = {k: np.zeros(N) for k in ['drums','bass','lead','keys']}
def put(stem, start, signal, gain=1):
    i = round(start * SR); length = min(len(signal), N-i)
    if length > 0: stems[stem][i:i+length] += signal[:length]*gain
def time(d): return np.arange(round(d*SR))/SR
def freq(midi): return 440 * 2**((midi-69)/12)
def pluck(note, duration, mellow=False):
    t=time(duration); f=freq(note)
    y=sum(np.sin(2*np.pi*f*h*t + .03*h)*np.exp(-t*(2+h*.65))*h**(-1.6 if mellow else 1.2) for h in range(1,9))
    return y*np.minimum(t/.004,1)*np.minimum((duration-t)/.03,1)
roots=[45,41,48,43]*4
chords=[[57,60,64],[53,57,60],[55,60,64],[55,59,62]]*4
# 16 bars = 32 seconds; last half second fades naturally.
for bar in range(16):
    start=bar*2
    for beat in [0,1,2,3]:
        t=time(.3); phase=2*np.pi*(46*t + 75*.035*(1-np.exp(-t/.035)))
        if beat in (0,2): put('drums',start+beat*.5,np.sin(phase)*np.exp(-t*17),.48)
        if beat in (1,3):
            noise=rng.normal(0,1,len(t)); noise=np.r_[0,np.diff(noise)]
            put('drums',start+beat*.5,(noise*.15+np.sin(2*np.pi*180*t)*.2)*np.exp(-t*24),.48)
    for eighth in range(8):
        t=time(.11); noise=rng.normal(0,1,len(t)); noise=np.r_[0,np.diff(noise)]
        put('drums', start+eighth*.25,noise*np.exp(-t*55), .032 if eighth%2 else .05)
    for beat,step in [(0,0),(.75,0),(1.5,7),(2,12),(3,7),(3.5,0)]:
        t=time(.38); f=freq(roots[bar]+step)
        y=(np.sin(2*np.pi*f*t)+.22*np.sin(4*np.pi*f*t))*np.minimum(t/.008,1)*np.exp(-t*7)
        put('bass',start+beat*.5,y,.26)
    for j,note in enumerate(chords[bar]): put('keys',start+j*.018,pluck(note,1.8,True),.075)
    pattern=[0,1,2,1,2,1,0,1] if bar%2==0 else [2,1,0,1,2,1,0,0]
    for j,index in enumerate(pattern):
        note=chords[bar][index]+12
        if bar%4==3 and j==7: note=69
        put('lead',start+j*.25,pluck(note,.65),.13 if j%2==0 else .1)
# Common gain and common fade preserve exact additive alignment.
fade=np.ones(N); fade[-int(.6*SR):]=np.linspace(1,0,int(.6*SR)); fade[:220]=np.linspace(0,1,220)
peak=np.max(np.abs(sum(stems.values())*fade)); gain=.86/peak
quant={k: np.rint(v*fade*gain*32767).astype(np.int16) for k,v in stems.items()}
quant['mix']=sum(v.astype(np.int32) for v in quant.values()).astype(np.int16)
ROOT.mkdir(parents=True,exist_ok=True)
manifest=[]
for k,data in quant.items():
    path=ROOT/f'{k}.wav'
    with wave.open(str(path),'wb') as f: f.setnchannels(1); f.setsampwidth(2); f.setframerate(SR); f.writeframes(data.astype('<i2').tobytes())
    manifest.append(dict(id=f'afterglow-{k}',songId='afterglow-v1',stem={'mix':'Full mix','lead':'Plucked lead','keys':'Keys','bass':'Bass','drums':'Drums'}[k],title='Afterglow on the lake',filename=path.name,size=path.stat().st_size,sha256=hashlib.sha256(path.read_bytes()).hexdigest(),durationMs=32000,sampleRate=SR,format='audio/wav'))
manifest.sort(key=lambda t:t['stem']!='Full mix')
(ROOT/'manifest.json').write_text(json.dumps(manifest,indent=2)+'\n')
print(f'Created {len(manifest)} aligned files: 32 s, {SR} Hz, PCM16. Peak {max(abs(quant["mix"].astype(int)))/32767:.3f}')
