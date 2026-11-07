"""Register real public speech and independent published text; never invent speaker timing labels."""
import hashlib
import json
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parent.parent

def metadata(path):
    data = json.loads(subprocess.check_output(['ffprobe', '-v', 'error', '-show_entries', 'format=duration', '-of', 'json', str(path)]))
    with path.open('rb') as stream:
        digest = hashlib.file_digest(stream, 'sha256').hexdigest()
    return {'sha256': digest, 'duration_ms': round(float(data['format']['duration']) * 1000)}

def main():
    source = ROOT / '.models/sherpa-onnx-whisper-tiny/test_wavs'
    destination = ROOT / 'artifacts/test-results/corpus'
    destination.mkdir(parents=True, exist_ok=True)
    rows = []
    for line in (source / 'trans.txt').read_text().splitlines():
        name, text = line.split(' ', 1)
        if name not in ('0.wav', '1.wav'):
            continue
        row = dict(id='english-' + name[0], fixture='english.wav' if name == '0.wav' else 'english-second.wav',
                   category='real_public_monologue', language='en', reference_text=text,
                   reference_source='Published trans.txt inside the pinned sherpa-onnx-whisper-tiny archive',
                   archive_sha256='c46116994e539aa165266d96b325252728429c12535eb9d8b6a2b10f129e66b1',
                   local_source=str((source / name).relative_to(ROOT)), **metadata(source / name))
        row.update(range_start_ms=0, range_end_ms=row['duration_ms'], range_scope='entire_file',
                   speaker_count=1, speech_boundary_reference=False, diarization_reference=False,
                   allowed_metric='WER only; full-file ranges are not manually annotated speech boundaries')
        speaker = 'librispeech_1089' if name == '0.wav' else 'librispeech_1221'
        reference = dict(transcript=[dict(speaker_id=speaker, start_ms=0, end_ms=row['duration_ms'], text=text)],
                         annotation=dict(text_source='published independent trans.txt', speaker_source='LibriSpeech corpus speaker ID',
                                         range_scope='entire source clip', speech_boundaries_verified=False,
                                         allowed_metric='WER only, not an independently annotated DER reference'))
        (destination / f"{row['id']}-reference.json").write_text(json.dumps(reference, ensure_ascii=False, indent=2), encoding='utf-8')
        rows.append(row)
    rows.append(dict(id='public-two-speakers', category='real_public_dialogue', fixture='two-speakers.wav',
                     source='https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-segmentation-models/1-two-speakers-en.wav',
                     **metadata(ROOT / '.models/two-speakers.wav'), reference_text=None, diarization_reference=False,
                     allowed_metric='Functional continuity checks only; no independent DER reference'))
    rows.extend([
        dict(id='noise-derived', category='synthetic_noise_on_real_speech', fixture='noisy-test.wav', independent_reference=False),
        dict(id='overlap-derived', category='synthetic_mix_of_two_real_recordings', fixture='overlap.wav', independent_reference=False),
    ])
    (destination / 'manifest.json').write_text(json.dumps({'clips': rows, 'private_audio_policy': 'Private interviews and transcripts stay outside Git in .private-validation; draft ASR is not reference text'}, ensure_ascii=False, indent=2), encoding='utf-8')
    print(f'Registered {len(rows)} clips; 2 published independent text references; no fabricated DER or timing labels')

if __name__ == '__main__':
    main()
