param([string]$Serial = 'emulator-5554', [string]$Python = 'python')
$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath (Split-Path $PSScriptRoot -Parent)
$adb = Join-Path $env:LOCALAPPDATA 'Android/Sdk/platform-tools/adb.exe'
$Audio = '.models/sherpa-onnx-small-zipformer-ru-2024-09-18/test_wavs/0.wav'
function Adb {
    & $adb -s $Serial @args
    if ($LASTEXITCODE -ne 0) { throw "adb failed: $args" }
}
if (!(Test-Path -LiteralPath $Audio)) { throw "Missing test audio: $Audio" }
if (!(Get-Command ffmpeg -ErrorAction SilentlyContinue)) { throw 'Install ffmpeg for the stereo MP3 decoder test' }
& ffmpeg -hide_banner -loglevel error -y -i .models/sherpa-onnx-small-zipformer-ru-2024-09-18/test_wavs/0.wav -ar 44100 -ac 2 .models/test-stereo.mp3
if ($LASTEXITCODE -ne 0) { throw 'MP3 fixture generation failed' }
& ./gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest --console=plain
if ($LASTEXITCODE -ne 0) { throw 'Build failed' }
Adb install -r app/build/outputs/apk/debug/app-debug.apk
Adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
# Models are prepared from APK assets by the application and instrumentation setup.
Adb shell run-as team.replika mkdir -p files
Adb push $Audio /data/local/tmp/replika-test.wav
Adb shell run-as team.replika cp /data/local/tmp/replika-test.wav files/test.wav
Adb push .models/two-speakers.wav /data/local/tmp/replika-two-speakers.wav
Adb shell run-as team.replika cp /data/local/tmp/replika-two-speakers.wav files/two-speakers.wav
Adb push .models/sherpa-onnx-small-zipformer-ru-2024-09-18/test_wavs/1.wav /data/local/tmp/replika-test1.wav
Adb shell run-as team.replika cp /data/local/tmp/replika-test1.wav files/test1.wav
Adb push .models/test-stereo.mp3 /data/local/tmp/replika-stereo.mp3
Adb shell run-as team.replika cp /data/local/tmp/replika-stereo.mp3 files/test-stereo.mp3
& ffmpeg -hide_banner -loglevel error -y -stream_loop 4 -i .models/sherpa-onnx-small-zipformer-ru-2024-09-18/test_wavs/1.wav -ar 44100 -ac 2 .models/window-test.mp3
if ($LASTEXITCODE -ne 0) { throw 'Window fixture generation failed' }
Adb push .models/window-test.mp3 /data/local/tmp/window-test.mp3
Adb shell run-as team.replika cp /data/local/tmp/window-test.mp3 files/window-test.mp3
if (!(Test-Path -LiteralPath '.models/sherpa-onnx-whisper-tiny/test_wavs/0.wav')) { throw 'Run setup-engine.ps1 for Whisper fixtures' }
Adb push .models/sherpa-onnx-whisper-tiny/test_wavs/0.wav /data/local/tmp/replika-english.wav
Adb shell run-as team.replika cp /data/local/tmp/replika-english.wav files/english.wav
& ffmpeg -hide_banner -loglevel error -y -i .models/sherpa-onnx-small-zipformer-ru-2024-09-18/test_wavs/1.wav -i .models/sherpa-onnx-whisper-tiny/test_wavs/0.wav -filter_complex '[0:a]aresample=16000,aformat=channel_layouts=mono[a];[1:a]aresample=16000,aformat=channel_layouts=mono[b];[a][b]amix=inputs=2:normalize=1' -t 6 .models/overlap.wav
if ($LASTEXITCODE -ne 0) { throw 'Overlap fixture generation failed' }
Adb push .models/overlap.wav /data/local/tmp/replika-overlap.wav
Adb shell run-as team.replika cp /data/local/tmp/replika-overlap.wav files/overlap.wav
& $Python scripts/make-noisy-fixture.py
if ($LASTEXITCODE -ne 0) { throw 'Python 3 is required to generate the deterministic noisy fixture; pass -Python with its executable path' }
Adb push .models/noisy-test.wav /data/local/tmp/replika-noisy.wav
Adb shell run-as team.replika cp /data/local/tmp/replika-noisy.wav files/noisy-test.wav
# Device radio state is left unchanged. The APK has no INTERNET permission.
$testOutput = Adb shell am instrument -w -r team.replika.test/androidx.test.runner.AndroidJUnitRunner
$testOutput | Tee-Object -FilePath artifacts/instrumentation.txt
if (($testOutput -join "`n") -notmatch 'OK \(\d+ tests\)') { throw 'Android instrumentation tests failed' }
New-Item -ItemType Directory -Force artifacts/test-results | Out-Null
Copy-Item -LiteralPath artifacts/instrumentation.txt -Destination artifacts/test-results/android-full.txt -Force
$evidence = @{
    'device-baseline.json' = 'device-current.json'
    'ultra-threads.json' = 'threads.json'
    'ultra-full-speed.json' = 'full-speed.json'
    'ultra-noise.json' = 'noise.json'
    'ultra-english.json' = 'english.json'
    'ultra-overlap.json' = 'overlap.json'
    'editor-proof.json' = 'editor-proof.json'
    'voice-continuity.json' = 'voice-continuity.json'
    'voice-recalculation.json' = 'voice-recalculation.json'
    'history-player-proof.json' = 'history-player-proof.json'
}
foreach ($entry in $evidence.GetEnumerator()) {
    Adb shell run-as team.replika cat "files/$($entry.Key)" | Set-Content "artifacts/test-results/$($entry.Value)" -Encoding utf8NoBOM
}
Adb shell run-as team.replika cat files/smoke-result.json | Set-Content artifacts/smoke-result.json -Encoding utf8NoBOM
Adb shell run-as team.replika cat files/second-result.json | Set-Content artifacts/second-result.json -Encoding utf8NoBOM
Adb shell run-as team.replika cat files/diarization-result.txt | Set-Content artifacts/diarization-result.txt -Encoding utf8NoBOM
Adb shell run-as team.replika cat files/ultra-threads.json | Set-Content artifacts/ultra-threads.json -Encoding utf8NoBOM
Adb shell run-as team.replika cat files/ultra-full-speed.json | Set-Content artifacts/ultra-full-speed.json -Encoding utf8NoBOM
