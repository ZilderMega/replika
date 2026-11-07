"""Extract a public four-speaker meeting excerpt and independent manual diarization labels."""
from pathlib import Path
import hashlib
import io
import json
import urllib.request
import wave
import argparse

ROOT = Path(__file__).resolve().parent.parent
AUDIO = 'https://groups.inf.ed.ac.uk/ami/AMICorpusMirror/amicorpus/HeadsetAudio/ES2002a.Mix-Headset.wav'
LABELS = 'https://raw.githubusercontent.com/pyannote/AMI-diarization-setup/main/only_words/rttms/train/ES2002a.rttm'
START, END = 49, 110

def request_range(start, size):
    request = urllib.request.Request(AUDIO, headers={'Range': f'bytes={start}-{start+size-1}', 'User-Agent': 'Replika-local-validation'})
    with urllib.request.urlopen(request, timeout=90) as response:
        if response.status != 206:
            remaining = start
            while remaining:
                part = response.read(min(65536, remaining))
                if not part: raise ValueError('Audio response truncated')
                remaining -= len(part)
        data = response.read(size)
    if len(data) != size: raise ValueError('Range length mismatch')
    return data

def main():
    global START, END
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--start', type=int, default=49)
    parser.add_argument('--end', type=int, default=110)
    parser.add_argument('--stem', default='ami-four-speakers')
    parser.add_argument('--from-clip', type=Path, help='Reuse the verified 49–110 s excerpt without downloading again')
    args = parser.parse_args()
    START, END = args.start, args.end
    assert 0 <= START < END and args.stem.replace('-', '').isalnum()
    if args.from_clip:
        provenance = json.loads((ROOT / 'artifacts/test-results/corpus/ami-reference.json').read_text())
        assert hashlib.sha256(args.from_clip.read_bytes()).hexdigest() == provenance['audio_sha256']
        assert 49 <= START < END <= 110
        with wave.open(str(args.from_clip)) as audio:
            rate, channels, width = audio.getframerate(), audio.getnchannels(), audio.getsampwidth()
            audio.setpos((START-49)*rate); pcm = audio.readframes((END-START)*rate)
    else:
        header = io.BytesIO(request_range(0, 4096))
        with wave.open(header) as audio:
            rate, channels, width = audio.getframerate(), audio.getnchannels(), audio.getsampwidth()
            offset = header.tell()
        framesize = channels * width
        pcm = request_range(offset + START * rate * framesize, (END - START) * rate * framesize)
    target = ROOT / f'.models/{args.stem}.wav'
    with wave.open(str(target), 'wb') as audio:
        audio.setparams((channels, width, rate, 0, 'NONE', 'not compressed')); audio.writeframes(pcm)
    cached = ROOT / '.models/ami-es2002a.rttm'
    annotation = cached.read_bytes() if cached.exists() else urllib.request.urlopen(LABELS, timeout=30).read()
    spans = []
    for line in annotation.decode().splitlines():
        fields = line.split()
        a, b = float(fields[3]), float(fields[3]) + float(fields[4])
        if b > START and a < END:
            spans.append(dict(speaker_id=fields[7], start_ms=round((max(a, START) - START) * 1000),
                              end_ms=round((min(b, END) - START) * 1000)))
    result = dict(id=f'ami-es2002a-{START}-{END}', category='real_public_meeting', audio_source=AUDIO,
                  labels_source=LABELS, labels_provenance='Only-words RTTM derived from AMI manual annotations 1.6.2',
                  source_start_ms=START * 1000, duration_ms=(END - START) * 1000,
                  audio_sha256=hashlib.sha256(target.read_bytes()).hexdigest(),
                  source_rttm_sha256=hashlib.sha256(annotation).hexdigest(), diarization_reference=spans,
                  transcript_reference=False, scope='Development diagnostic; no unseen-corpus or official score claim')
    destination = ROOT / f'artifacts/test-results/corpus/{args.stem}-reference.json'
    destination.parent.mkdir(parents=True, exist_ok=True)
    destination.write_text(json.dumps(result, indent=2), encoding='utf-8')
    print(f"Downloaded {len(pcm)} PCM bytes, {END-START}s, {len(set(s['speaker_id'] for s in spans))} speakers, {len(spans)} independently annotated turns")

if __name__ == '__main__': main()
