"""Summarize public-fixture Android measurements, without scoring private unannotated audio."""
import json
from pathlib import Path
from statistics import median
from collections import defaultdict
from evaluate import words, edit_distance

ROOT = Path(__file__).resolve().parent.parent

def main():
    directory = ROOT / 'artifacts/test-results/samsung'
    data = json.loads((directory / 'physical-validation.json').read_text(encoding='utf-8-sig'))
    corpus = json.loads((ROOT / 'artifacts/test-results/corpus/manifest.json').read_text())
    references = {row['fixture']: row['reference_text'] for row in corpus['clips'] if row.get('reference_text')}
    groups = defaultdict(list)
    for run in data['pipeline_runs']:
        groups[(run['provider'], run['threads'])].append(run)
    timings = []
    texts = {tuple(words(' '.join(row['text'] for row in run['raw']['transcript']))) for run in data['pipeline_runs']}
    for (provider, threads), runs in groups.items():
        timings.append(dict(provider=provider, threads=threads, samples_ms=[r['elapsed_ms'] for r in runs],
                            median_ms=median(r['elapsed_ms'] for r in runs), audio_ms=runs[0]['duration_ms'],
                            peak_sampled_pss_mib=round(max(r['peak_sampled_process_pss_kib'] for r in runs) / 1024, 1)))
    quality = []
    for run in data['language_comparison']:
        reference = words(references[run['fixture']])
        text = ' '.join(row['text'] for row in run['result']['transcript'])
        errors = edit_distance(reference, words(text))
        quality.append(dict(fixture=run['fixture'], mode=run['mode'], reference_words=len(reference),
                            word_errors=errors, wer=errors / len(reference), elapsed_ms=run['elapsed_ms']))
    cpu = sorted((r for r in timings if r['provider'] == 'cpu'), key=lambda r: r['median_ms'])
    # Avoid changing a default for a sub-percent difference measured on only three short sequential runs.
    best = cpu[0]
    four = next((r for r in cpu if r['threads'] == 4), None)
    recommended = four if four and four['median_ms'] <= best['median_ms'] * 1.05 else best
    result = dict(device=data['device'], sdk=data['sdk'], pipeline=timings, same_asr_words_across_runs=len(texts) == 1,
                  language=quality, recommended_provider='cpu', recommended_threads=recommended['threads'],
                  recommendation_rule='Keep four threads when within 5% of fastest CPU median; only 3 sequential short-file runs, no causal thermal comparison',
                  limitations=['2 public English clips are not an unseen representative corpus',
                               'WER uses literal published spelling: e.g. for ever and forever differ',
                               'These short speed/language runs have no DER labels; separate AMI evaluation has human RTTM',
                               'NNAPI requested, actual NPU execution not established',
                               'PSS sampled every 500ms, not guaranteed peak or whole-system RAM',
                               'Battery temperature is not CPU temperature; battery percentage is not isolated app energy'])
    (directory / 'summary.json').write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding='utf-8')
    lines = ['# Проверка на физическом Samsung', '', f"Устройство: {data['device']}, Android API {data['sdk']}. Все аудиовычисления выполнены на телефоне.",
             '', '## Скорость', '', 'Публичная запись 35,4 с, уже находящаяся в приложении. По три полных прохода на конфигурацию; медиана. Включены декодирование, загрузка моделей этапов, ASR, диаризация и быстрая пунктуация. Начальная установка/подготовка моделей не включена.', '']
    for row in timings:
        lines.append(f"- {row['provider']}, {row['threads']} потока: {row['median_ms']/1000:.3f} с; прогоны {row['samples_ms']}; максимум выборок PSS {row['peak_sampled_pss_mib']} МиБ.")
    lines += ['', f"Слова ASR во всех проходах совпали: {len(texts)==1}. Выбран CPU, {recommended['threads']} потока. Разницу меньше 5% на этом коротком тесте не используем как основание менять четыре потока. Число потоков доступно пользователю.",
              '', 'Порядок фиксирован, фоновые приложения и температура окружения не контролировались. NNAPI не считается доказательством NPU. Замеры быстрого режима не включают генеративную Qwen.',
              '', '## Иноязычная речь', '', 'Два реальных публичных английских монолога с опубликованными текстами из trans.txt, включённого в зафиксированный комплект sherpa. Длительности/хэши/источники: artifacts/test-results/corpus/manifest.json. Это небольшая диагностическая выборка, а не общая оценка интервью.', '']
    for row in quality:
        lines.append(f"- {row['fixture']}, {row['mode']}: WER {row['wer']:.2%} ({row['word_errors']} ошибок на {row['reference_words']} слов), {row['elapsed_ms']} мс.")
    lines += ['', 'Русский ASR не предназначен для произвольной английской речи. Для выбранного фрагмента используется многоязычный Whisper с transcribe, без перевода. Автоматический словарь отдельных названий удалён. Основной проход всего файла остаётся русскоязычным; автоматическая точная транскрибация любого смешанного интервью не заявляется.',
              '', 'Второй английский пример содержит ошибки и варианты написания (например, слитное/раздельное слово); исходный эталон не изменяли ради улучшения WER. WER игнорирует регистр/пунктуацию, но не переводит и не транслитерирует слова.',
              '', '## Память, температура и заряд', '', f"До прогона: {data['initial_environment']}. После: {data['final_environment']}.",
              '', 'PSS измеряется раз в 500 мс и может пропустить пик. Температура относится к батарее, не к CPU. Разница процентов заряда относится ко всему устройству; это не измерение энергии приложения.',
              '', '## Приватность и воспроизводимость', '', 'Для ускорения Wi-Fi-обновления использован debug APK только с кодом и манифестом моделей: уже установленные модели сверены по манифесту и SHA-256. Обычная сборка/комплект сдачи содержит все модели. Такой облегчённый APK нельзя использовать для чистой установки или восстановления удалённых моделей.',
              '', 'После измерений на телефон возвращён полный автономный APK; отдельно проверено восстановление повреждённой модели из его ресурсов. Реальный длинный прогон и проверка продолжения описаны в LONG_RECORDING_VALIDATION.md; независимая человеческая разметка AMI — в ANNOTATED_SPEAKER_VALIDATION.md.',
              '', 'Личные записи, их пути, выдержки и стенограммы не входят в публичные артефакты. Для них используется игнорируемая .private-validation. Без проверенной ручной разметки личной записи не рассчитывается WER/DER. Синтетические шум/наложение явно отделены от реального диалога.',
              '', 'Исходные измерения: artifacts/test-results/samsung/physical-validation.json; вычисления: summary.json; повторный расчёт: python scripts/summarize-phone-validation.py.', '']
    (ROOT / 'docs/PHONE_VALIDATION.md').write_text('\n'.join(lines), encoding='utf-8')
    print(json.dumps(result, ensure_ascii=False, indent=2))

if __name__ == '__main__':
    main()
