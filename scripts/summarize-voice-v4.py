"""Summarize measured voice-v4 evidence without using private audio as reference truth."""
import json
import re
from pathlib import Path
from statistics import median
from evaluate import diarization

ROOT = Path(__file__).resolve().parent.parent
DIR = ROOT/'artifacts/test-results/voice-v4'
def read(name):
    return json.loads((DIR/name).read_text(encoding='utf-8-sig'))
def reference(fixture):
    return json.loads((ROOT/'artifacts/test-results/corpus'/(Path(fixture).stem+'-reference.json')).read_text())['diarization_reference']
def hypothesis(spans):
    return [dict(start_ms=a, end_ms=b, speaker_id=f'speaker_{s}' if s>=0 else f'speaker_review_{-s}' if s < -1 else 'speaker_unknown') for a,b,s in spans]

def main():
    benchmark = read('diarization-optimization.json')
    workers = []
    for count in (1, 2):
        runs = [r for r in benchmark['runs'] if r.get('workers') == count]
        scores = [dict(fixture=result['fixture'], score=diarization(reference(result['fixture']), hypothesis(result['spans']))) for run in runs for result in run['results']]
        workers.append(dict(workers=count, threads_per_worker=4//count, samples_ms=[r['elapsed_ms'] for r in runs],
                            median_ms=median(r['elapsed_ms'] for r in runs), max_sampled_pss_mib=max(r['sampled_process_pss_kib'] for r in runs)/1024, scores=scores))
    initial = next(r['results'] for r in benchmark['runs'] if 'results' in r)
    same_spans = all(r['results'] == initial for r in benchmark['runs'] if 'results' in r)
    refinement = read('speaker-refinement-proof.json')
    score = diarization(reference(refinement['fixture']), refinement['candidate']['transcript'])
    native = read('ami-validation.json')
    native_scores = [dict(fixture=r['fixture'], threshold=r['threshold'], score=diarization(reference(r['fixture']), hypothesis(r['spans']))) for r in native['runs']]
    window_scores = [dict(fixture=r['fixture'], block_seconds=r['block_seconds'], elapsed_ms=r['elapsed_ms'],
                          score=diarization(reference(r['fixture']), hypothesis(r['spans'])))
                     for r in read('diarization-window-benchmark.json')['runs']]
    result = dict(device=benchmark['device'], voice_matcher_version=4, workers=workers,
                  parallel_reduction_fraction=1-workers[1]['median_ms']/workers[0]['median_ms'],
                  scheduling_same_native_spans=same_spans, global_matcher_scores=native_scores, window_experiments=window_scores,
                  refinement_ms=refinement['refinement_ms'], refinement_with_human_word_times_der=score,
                  rematch_ms=refinement['rematch_ms'], rematch_native_calls=refinement['native_calls_on_rematch'],
                  limitations=['Same two AMI excerpts used during development, not a new independent corpus',
                               'Refinement alignment uses human English word times; not full ASR-plus-diarization accuracy',
                               'Fixed-order 3-repeat scheduling benchmark; sampled PSS, not guaranteed peak',
                               'No full 82-minute v4 run or new Samsung timing; old phone report remains v1/v3 evidence'])
    (DIR/'summary.json').write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding='utf-8')
    lines = ['# Голоса v4: изменения и проверка', '',
             '## Что работает', '',
             '- Голосовые признаки и локальные интервалы сохраняются отдельно от глобальных ID в контрольной точке. Повторный пересчёт использует их без декодирования, ASR, загрузки моделей диаризации и повторных нативных вызовов. Старым записям сначала нужно подготовить этот кэш; первый пересчёт может остаться долгим.',
             '- В конце прохода неподтверждённые ранние голоса сравниваются с накопленной банкой профилей. Уже подтверждённые автоматические назначения фиксируются; неоднозначные совпадения не принуждаются к ID. Это ограниченное согласование, а не обещание исправить все ошибочные объединения.',
             '- В редакторе есть «Уточнить смену голоса, сохранив текст»: WeSpeaker ResNet34 только для выбранных 30 с. Используются реальные времена ASR-слов; если ручной текст им не соответствует, операция отклоняется с сохранением текста. Новые голоса имеют временные ID и требуют проверки. Произвольное наложение не превращается в выдуманный второй текст.',
             '- Можно подтвердить чистые 2–9 с и указать ID. Приложение хранит образец локально и предлагает похожие реплики. В диалоге есть прослушивание; ни одно предложение не выбрано автоматически. Применяются только отмеченные ID, отдельно от исходника, без изменения слов и времён.',
             '- В настройках добавлены два одновременных блока CPU, выключенные по умолчанию. Выбранное число потоков делится между двумя независимыми моделями. Готовые блоки и профили сохраняются по порядку; отмена ждёт завершения нативных вызовов. Для NNAPI или одного потока используется один блок.',
             '- Убраны лишние копии массивов PCM перед нативными вызовами; короткие участки по-прежнему дополняются до безопасной длины. Отдельно измеряются загрузка моделей, нативный проход, дополнительные голосовые образцы, сопоставление, согласование и попадания в кэш.',
             '', '## Фактические измерения', '', f"Устройство: {benchmark['device']}. Пара публичных AMI-фрагментов 30 и 31 с, три прохода на режим. Измеряется нативная диаризация с созданием моделей и декодированием, без ASR, пунктуации и внешнего сопоставления профилей. Порядок фиксирован, фоновая нагрузка не изолирована.", '']
    for row in workers:
        lines.append(f"- {row['workers']} заданий × {row['threads_per_worker']} потока: медиана {row['median_ms']/1000:.3f} с; прогоны {row['samples_ms']} мс; максимум выборок PSS {row['max_sampled_pss_mib']:.1f} МиБ.")
    lines += ['', f"Сокращение времени пары: {result['parallel_reduction_fraction']:.1%}; одинаковые нативные интервалы во всех повторах: {same_spans}. PSS измерялся каждые 100 мс и может пропустить пик. Это не результат на Samsung и не время обработки часового интервью.",
              '', f"Уточнение голосов на 30 с AMI: {refinement['refinement_ms']/1000:.3f} с. Сохранность текста проверена на 67 словах независимой ручной разметки AMI. DER после привязки к этим человеческим временам: {score['der']:.2%}, без collar, с наложениями. Это проверка уточнения при заданных человеческих временах, а не оценка русского ASR или всей записи.",
              '', f"Пересчёт по кэшу на том же 30-секундном файле: {refinement['rematch_ms']/1000:.3f} с, нативных вызовов — 0. Слова ASR не изменились. Генеративная Qwen в эти замеры не включена.",
              '', 'Текущий автоматический проход, порог 0,5, те же размеченные фрагменты:']
    for row in native_scores:
        if row['threshold'] == .5:
            lines.append(f"- {row['fixture']}: DER {row['score']['der']:.2%}. Ошибки остаются существенными.")
    lines += ['', '## Почему не уменьшен блок по умолчанию', '',
              'Отдельно проверены блоки 6 и 8 с с полноценным сопоставлением голосов, а не только временем вызова модели. Время ниже включает создание модели, нативный проход, образцы и согласование; загрузка аудио выполнена перед отсчётом. Режимы проверялись по одному разу в фиксированном порядке.']
    for row in window_scores:
        lines.append(f"- {row['fixture']}, блок {row['block_seconds']} с: {row['elapsed_ms']/1000:.3f} с, DER {row['score']['der']:.2%}.")
    lines += ['', 'Короткие блоки быстрее, но ухудшили DER на втором фрагменте: результат первого нельзя выдавать за общее улучшение. Поэтому основной блок оставлен 120 с; отдельный быстрый режим 6–8 с не включён в приложение.']
    lines += ['', '## Проверки и границы выводов', '',
              '53 JVM-теста и Lint без ошибок. Первый целевой Android-прогон: 8 из 9 сценариев; UI-проверка выявила проблему ожидания/состояния редактора. Исправлены привязка результата поиска к запросу и обновление полей по актуальному состоянию; тест ожидает готовности реального интерфейса. Повторные проверки UI — android-ui-after-fix.txt. Отдельно прошёл диагностический тест коротких блоков. В сумме проверены 10 уникальных целевых сценариев, а не один полный успешный прогон всех тестов проекта. Исходный неуспешный лог сохранён в android-before-ui-fix.txt.',
              '', 'Первый тест ошибочно ожидал несколько участников от русского ASR на английском AMI: ASR не дал достаточного текста для такой проверки. Его заменили проверкой по настоящим человеческим словам/временам из официального AMI 1.6.2; проверка сохранения реального ASR при пересчёте осталась отдельной. Источник: https://groups.inf.ed.ac.uk/ami/download/ ; архив и SHA-256 записаны в corpus/ami-*-words.json. Новая разметка не получена из результата нашей модели.',
              '', 'Эти фрагменты уже использовались при разработке; это не проверка на новых встречах/участниках. Полного 82-минутного прогона v4 и нового физического Samsung-замера нет. Исторические 22:51 относятся к v1. Точность и ускорение новых функций на произвольном русском интервью не гарантируются.',
              '', 'Проверенный WeSpeaker взят из официальной sherpa-модели, SHA-256 закреплён в ModelFiles.EXTRA_HASHES и docs/model-manifest.json. Модель распространяется отдельно от CAMPPlus и применяется только по запросу пользователя. Атрибуция и CC BY 4.0 включены в APK. Базовый манифест сохранён, чтобы добавление этой модели не сбрасывало старые ASR-контрольные точки.',
              '', 'Повторить: `./scripts/test-voice-v4.ps1 -Serial <ID из adb devices> -Python <python.exe> -Benchmark`. Скрипт устанавливает полный APK, готовит публичные данные, запускает проверки и сохраняет доказательства. Личные аудио и стенограммы в Git/ZIP не включаются.', '']
    completed = re.search(r'OK \((\d+) tests\)', (DIR/'android.txt').read_text(encoding='utf-8-sig'))
    if completed:
        lines += [f"Последний запуск целевого набора: успешно {completed.group(1)} тестов. Исторический неуспешный прогон сохранён отдельно.", '']
    (ROOT/'docs/VOICE_V4.md').write_text('\n'.join(lines), encoding='utf-8')
    print(json.dumps({k:result[k] for k in ['device','parallel_reduction_fraction','scheduling_same_native_spans','refinement_ms','rematch_ms']}, indent=2))

if __name__ == '__main__': main()
