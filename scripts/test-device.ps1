param([string]$Serial = 'emulator-5554')
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
# Device radio state is left unchanged. The APK has no INTERNET permission.
$testOutput = Adb shell am instrument -w -r team.replika.test/androidx.test.runner.AndroidJUnitRunner
$testOutput | Tee-Object -FilePath artifacts/instrumentation.txt
if (($testOutput -join "`n") -notmatch 'OK \(\d+ tests\)') { throw 'Android instrumentation tests failed' }
Adb shell run-as team.replika cat files/smoke-result.json | Set-Content artifacts/smoke-result.json -Encoding utf8NoBOM
Adb shell run-as team.replika cat files/second-result.json | Set-Content artifacts/second-result.json -Encoding utf8NoBOM
Adb shell run-as team.replika cat files/diarization-result.txt | Set-Content artifacts/diarization-result.txt -Encoding utf8NoBOM
