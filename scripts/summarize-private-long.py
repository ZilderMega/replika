"""Export an allowlisted performance summary; never export the private transcript or source path."""
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent

def main():
    report = json.loads((ROOT / '.private-validation/long-result.json').read_text(encoding='utf-8-sig'))
    run = report['run']
    result = dict(device=report['device'], threads=report['threads'], provider='cpu',
                  duration_ms=run['duration_ms'], elapsed_ms=run['elapsed_ms'], rtf=run['rtf'],
                  stages_ms=run['stages_ms'], peak_sampled_process_pss_kib=run['peak_sampled_process_pss_kib'],
                  battery_temperature_before_c=report['before']['battery_temperature_c'],
                  battery_temperature_after_c=report['after']['battery_temperature_c'],
                  thermal_status_before=report['before']['thermal_status'], thermal_status_after=report['after']['thermal_status'],
                  resumed_at_ms=report['resumed_at_ms'], preserved_committed_words=report['preserved_committed_words'],
                  voice_matcher_version=1, final_voice_matcher_version=3,
                  completeness='Instrumentation asserted recognizedMs == durationMs, diarizedSamples == samples, finished and retained committed ASR words',
                  accuracy='No independently annotated private reference; coverage is not word or speaker accuracy',
                  conditions='Already imported file, models installed, fast punctuation, no generative Qwen. Wireless ADB APK transfer overlapped the run; not an isolated energy or thermal benchmark.')
    output = ROOT / 'artifacts/test-results/samsung/long-summary.json'
    output.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding='utf-8')
    (ROOT / 'docs/LONG_RECORDING_VALIDATION.md').write_text('''# Реальная длинная запись на Samsung

Личное интервью пользователя: **81:58,520**. Полный быстрый конвейер на SM-S936B, CPU 4 потока: **22:51,132**, RTF **0,279** — примерно в 3,59 раза быстрее длительности записи. Импорт уже выполнен, модели установлены; генеративная Qwen в этот замер не входит.

- Подготовка звука: 111,177 с; ASR: 109,282 с; определение участников: 1147,066 с; быстрая пунктуация: 3,578 с. Диаризация заняла около 84% времени — это главный оставшийся резерв скорости.
- Выполнена остановка после сохранённых 6 с ASR и продолжение. Все первые 10 сохранённых слов остались неизменными. Проверены завершение, обработка всей длительности ASR и всех PCM-сэмплов диаризации. Это полнота обхода записи, а не доказательство правильности каждого слова.
- Максимум выборок PSS процесса: 382009 КиБ, около 373 МиБ; шаг 500 мс может пропустить пик. Температура батареи до общего прогона 35,0 °C, после 39,1 °C; thermal status изменился с 0 на 1. Это не температура CPU. Во время прогона передавался APK по ADB, поэтому расход/нагрев нельзя приписать только приложению.
- Этот длинный замер выполнен с VoiceMatcher v1. Исправления v3 проверены отдельно на коротких размеченных фрагментах; полный повторный 82-минутный замер v3 не выполнен.

Готовая запись доступна на телефоне в истории как «Длинная запись · 1:21:58». Исходный файл Telegram не изменён. Результат не является ручной разметкой: точное число участников неизвестно, WER/DER личной записи не вычислялись.

Пять коротких WAV для прослушивания и черновик аннотаций подготовлены в `.private-validation/review`. Черновые слова/ID получены из приложения и требуют проверки человеком. Личные аудио, пути и стенограмма исключены из Git и ZIP сдачи; публичный `long-summary.json` содержит только разрешённый список агрегатов.
''', encoding='utf-8')
    print(output)

if __name__ == '__main__':
    main()
