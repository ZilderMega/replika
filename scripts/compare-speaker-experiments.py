"""Summarize diagnostic model comparisons; candidate is not bundled in the production APK."""
import json
from pathlib import Path
from evaluate import diarization

ROOT = Path(__file__).resolve().parent.parent
DIR = ROOT / 'artifacts/test-results/samsung'

def main():
    rows = []
    for filename in ('ami-native-baseline.json', 'ami-candidate-embedding.json'):
        data = json.loads((DIR / filename).read_text(encoding='utf-8-sig'))
        for run in data['runs']:
            ref = json.loads((ROOT / 'artifacts/test-results/corpus' / (Path(run['fixture']).stem+'-reference.json')).read_text())['diarization_reference']
            hyp = [dict(start_ms=a, end_ms=b, speaker_id=f'speaker_{s}' if s >= 0 else 'speaker_unknown') for a,b,s in run['spans']]
            rows.append(dict(source=filename, fixture=run['fixture'], threshold=run['threshold'],
                             global_matcher=run.get('global_matcher', False), elapsed_ms=run['elapsed_ms'],
                             score=diarization(ref, hyp)))
    (DIR/'speaker-experiments-scores.json').write_text(json.dumps(rows, ensure_ascii=False, indent=2), encoding='utf-8')
    lines = ['# Диагностика модели и сопоставления голосов', '',
             'Те же два непересекающихся фрагмента AMI одного совещания. DER без collar, с наложениями; speaker_unknown не получает кредит личности. Это диагностическая выборка, не общая оценка и не конкурсный балл.', '']
    for row in rows:
        lines.append(f"- {row['source']}, {row['fixture']}, порог {row['threshold']}, глобальное сопоставление {row['global_matcher']}: DER {row['score']['der']:.2%}, {row['elapsed_ms']/1000:.3f} с.")
    lines += ['', 'Проверен официальный sherpa WeSpeaker ResNet34: SHA-256 5ef208a9da1453335308a6b6f4e6dfbd7e183a38b604de0a57664f45d257fe94. Источник: https://github.com/k2-fsa/sherpa-onnx/releases/tag/speaker-recongition-models . Он установлен только в тестовую папку телефона, не включён в production-манифест/автономный APK.',
              '', 'В локальном режиме кандидат точнее на этих фрагментах, но заметно медленнее. Пороги/выбор образцов CAMPPlus несовместимы с ним без калибровки: глобальное сопоставление ухудшает результат. Поэтому слепая замена модели не выполнена. Возможное следующее улучшение — отдельное точное уточнение выбранного участка, после проверки на новых русских интервью и замеров времени.',
              '', 'Ускоренный шаг сегментации также отвергнут: на публичном двухголосом примере он потерял часть участников. Быстрый проход сохраняет исходный шаг 0,1. Снижение одного порога не устранило ошибки AMI; текущая версия сохраняет неподтверждённые локальные голоса раздельно и допускает ручное исправление.']
    (ROOT/'docs/SPEAKER_EXPERIMENTS.md').write_text('\n'.join(lines)+'\n', encoding='utf-8')
    print(f'{len(rows)} diagnostic runs scored')

if __name__ == '__main__':
    main()
