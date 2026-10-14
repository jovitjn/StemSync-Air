#!/usr/bin/env python3
from pathlib import Path
import wave,json,hashlib
import numpy as np
root=Path(__file__).resolve().parents[1]/'app/src/main/assets/demo'
tracks=json.loads((root/'manifest.json').read_text())
data={}
for t in tracks:
 p=root/t['filename']
 assert hashlib.sha256(p.read_bytes()).hexdigest()==t['sha256']
 with wave.open(str(p)) as f:
  assert (f.getframerate(),f.getnchannels(),f.getsampwidth(),f.getnframes())==(44100,1,2,1411200)
  data[p.stem]=np.frombuffer(f.readframes(f.getnframes()),dtype='<i2').astype(np.int32)
assert np.array_equal(data['mix'],sum(data[k] for k in ['drums','bass','lead','keys']))
assert max(abs(data['mix']))<32767
print('PASS: five checksums; equal sample rates, channel counts and 32-second durations; mix exactly equals aligned integer stems; no clipping.')
