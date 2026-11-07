"""Make private review excerpts. ASR drafts are explicitly not independent reference annotations."""
import argparse
import json
from pathlib import Path
import subprocess
import wave

ROOT = Path(__file__).resolve().parent.parent

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True)
    args = parser.parse_args()
    import os
    adb = str(Path(os.environ['LOCALAPPDATA']) / 'Android/Sdk/platform-tools/adb.exe')
    def read(*command):
        return subprocess.check_output([adb, '-s', args.serial, 'exec-out', 'run-as', 'team.replika', *command], stderr=subprocess.PIPE)
    checkpoint = json.loads(read('cat', 'files/private-long-validation/checkpoint.json'))
    words = checkpoint['words']; spans = checkpoint['spans']; duration = checkpoint['samples'] / 16
    candidates = [('beginning', max(0, words[0][1]-1000), 20000), ('ending', max(0, duration-20000), 20000)]
    ordered = sorted((s for s in spans if s[2] >= 0), key=lambda s: s[0])
    for first, second in zip(ordered, ordered[1:]):
        if first[2] != second[2] and abs(second[0]-first[1]) < 1000:
            candidates.append(('proposed_turn_change', max(0, second[0]-5000), 12000)); break
    unknown = [s for s in spans if s[2] < 0]
    if unknown:
        longest = max(unknown, key=lambda s: s[1]-s[0])
        candidates.append(('uncertain_speaker', max(0, longest[0]-1000), 12000))
    for i, first in enumerate(ordered):
        second = next((s for s in ordered[i+1:] if s[0] < first[1] and s[2] != first[2] and min(s[1], first[1])-s[0] >= 150), None)
        if second:
            candidates.append(('proposed_overlap', max(0, second[0]-2000), 6000)); break
    destination = ROOT / '.private-validation/review'
    destination.mkdir(parents=True, exist_ok=True)
    entries = []
    for name, start, length in candidates:
        seconds = int(start // 1000)
        pcm = read('dd', 'if=files/private-long-validation/audio.pcm', 'bs=32000', f'skip={seconds}', f'count={length//1000}')
        if not pcm: continue
        pcm = pcm[:len(pcm)//2*2]
        path = destination / (name+'.wav')
        with wave.open(str(path), 'wb') as audio:
            audio.setparams((1, 2, 16000, 0, 'NONE', 'not compressed')); audio.writeframes(pcm)
        a, b = seconds*1000, seconds*1000+len(pcm)/32
        draft = ' '.join(w[0] for w in words if a <= (w[1]+w[2])/2 < b)
        entries.append(dict(file=path.name, source_start_ms=a, source_end_ms=round(b),
                            proposed_speaker_ids=sorted({s[2] for s in spans if s[1]>a and s[0]<b}),
                            draft_text=draft, annotation_status='unverified_asr_not_ground_truth',
                            category=name))
    (destination/'draft-annotations.json').write_text(json.dumps(dict(clips=entries,
        warning='Private data. Speaker IDs and text are drafts; confirm against audio before any WER/DER scoring. No automatic label is represented as manual truth.'), ensure_ascii=False, indent=2), encoding='utf-8')
    (destination/'README.md').write_text('# Приватная проверка\n\nФайлы содержат начало, конец и автоматически найденные спорные места реальной записи. Метки смены/наложения — кандидаты, не подтверждённые факты. В draft-annotations.json указаны времена относительно исходника и черновой текст ASR. Для эталона нужны прослушивание и исправление текста, ID и границ. Этот каталог исключён из Git и архива сдачи.\n', encoding='utf-8')
    print(f'Exported {len(entries)} private excerpts into ignored .private-validation/review; no claimed ground truth')

if __name__ == '__main__': main()
