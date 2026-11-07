"""Score phone diarization against independent human AMI labels, never against ASR pseudo-labels."""
import json
from pathlib import Path
from evaluate import diarization

ROOT = Path(__file__).resolve().parent.parent

def main():
    directory = ROOT / 'artifacts/test-results/samsung'
    data = json.loads((directory / 'ami-validation.json').read_text(encoding='utf-8-sig'))
    rows = []
    for run in data['runs']:
        reference_file = ROOT / 'artifacts/test-results/corpus' / (Path(run['fixture']).stem+'-reference.json')
        reference = json.loads(reference_file.read_text())['diarization_reference']
        hypothesis = [dict(start_ms=a, end_ms=b, speaker_id=f'speaker_{speaker}' if speaker >= 0 else
                           f'speaker_review_{-speaker}' if speaker < -1 else 'speaker_unknown') for a,b,speaker in run['spans']]
        score = diarization(reference, hypothesis, collar_ms=0, ignore_overlap=False)
        score250 = diarization(reference, hypothesis, collar_ms=250, ignore_overlap=False)
        no_overlap = diarization(reference, hypothesis, collar_ms=0, ignore_overlap=True)
        rows.append(dict(fixture=run['fixture'], threshold=run['threshold'], profiles=run['profiles'], provisional_voices=run.get('provisional_voices', 0),
                         elapsed_ms=run['elapsed_ms'], all_speech=score, collar_250ms=score250, ignore_overlap=no_overlap))
    result = dict(device=data['device'], voice_matcher_version=data['voice_matcher_version'], runs=rows,
                  unknown_policy='speaker_unknown is never optimally relabeled into a real person',
                  scope='Two disjoint development/validation excerpts of the same meeting, not a speaker-independent or contest test')
    (directory/'ami-scores.json').write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding='utf-8')
    lines = ['# Размеченная проверка смены говорящих', '', 'Источник: реальное совещание AMI ES2002a, Mix-Headset. Два непересекающихся фрагмента: 49–79 с (3 участника) и 79–110 с (4 участника). Эталон RTTM получен из ручной разметки AMI 1.6.2, а не из результата нашей модели.',
             '', 'Разметка: https://github.com/pyannote/AMI-diarization-setup ; описание происхождения: https://github.com/pyannote/AMI-diarization-setup/blob/main/README.md . Аудио: https://groups.inf.ed.ac.uk/ami/AMICorpusMirror/amicorpus/HeadsetAudio/ES2002a.Mix-Headset.wav . SHA-256 и смещения находятся в artifacts/test-results/corpus/ami-*-reference.json.',
             '', 'Все вычисления диаризации выполнены на Samsung. Приведён DER с оптимальным соответствием анонимных ID, с учётом наложений и без collar; рядом отдельно вариант collar 250 мс. speaker_unknown не получает кредит как установленный участник. Низкое число кластеров или успешный запуск не считаются доказательством точности.', '']
    for row in rows:
        lines.append(f"- {row['fixture']}, порог {row['threshold']}, профилей {row['profiles']}, временных голосов {row['provisional_voices']}: DER {row['all_speech']['der']:.2%}; с collar 250 мс {row['collar_250ms']['der']:.2%}; время {row['elapsed_ms']} мс.")
    lines += ['', 'Это диагностические фрагменты одного совещания. Они не заменяют независимый корпус русских интервью и не определяют конкурсный балл. Подбор порогов по этим данным не должен выдаваться за проверку на новых участниках. Полные компоненты ошибок, знаменатели и режим без наложений сохранены в ami-scores.json.',
              '', 'Версия 3 сохраняет разные неподтверждённые локальные голоса как speaker_review_N; при DER это разные гипотезы кластеров с одним оптимальным соответствием каждому, а не установленная личность. В интерфейсе они требуют проверки. speaker_unknown остаётся воздержанием и не сопоставляется с эталонной личностью.',
              '', 'Сравнение с предыдущей диагностической v2 при том же пороге 0,5: DER 44,34% → 38,83% на первом фрагменте и 72,33% → 39,21% на втором. Исходные v2 сохранены как ami-validation-v2.json и ami-scores-v2.json. Улучшение не устраняет ошибки исходной сегментации и не доказывает стабильность ID на длинном интервью. Отдельное сравнение моделей — SPEAKER_EXPERIMENTS.md.',
              '', 'Текстовый WER здесь не вычисляется: RTTM содержит участников и времена, а не текст. Реальная личная запись пользователя не использовалась как автоматически размеченный эталон.', '']
    (ROOT/'docs/ANNOTATED_SPEAKER_VALIDATION.md').write_text('\n'.join(lines), encoding='utf-8')
    print(json.dumps(result, ensure_ascii=False, indent=2))

if __name__ == '__main__': main()
