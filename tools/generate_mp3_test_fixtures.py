"""Generate original MP3 regression fixtures; requires the test-only lameenc package.
The audio comes from this project's MIT-licensed demo, not commercial music.
Run from the project root: python3 tools/generate_mp3_test_fixtures.py
"""
from pathlib import Path
import array
import json
import wave
import lameenc

assets = Path('app/src/main/assets/demo')
output = Path('app/src/androidTest/assets')
output.mkdir(parents=True, exist_ok=True)
name = json.loads((assets / 'manifest.json').read_text())[0]['filename']
with wave.open(str(assets / name)) as source:
    pcm = source.readframes(44100 * 4)
encoder = lameenc.Encoder()
encoder.set_bit_rate(128)
encoder.set_in_sample_rate(44100)
encoder.set_channels(1)
encoder.set_quality(2)
(output / 'original-demo.mp3').write_bytes(encoder.encode(pcm) + encoder.flush())
samples = array.array('h', pcm)
stereo = array.array('h', (v for x in samples for v in (x, x)))
encoder = lameenc.Encoder()
encoder.set_in_sample_rate(44100)
encoder.set_out_sample_rate(32000)
encoder.set_channels(2)
encoder.set_vbr(lameenc.VBR_MTRH)
encoder.set_vbr_quality(4)
(output / 'original-stereo-vbr.mp3').write_bytes(encoder.encode(stereo.tobytes()) + encoder.flush())
