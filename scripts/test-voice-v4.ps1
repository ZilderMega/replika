param([string]$Serial='emulator-5556', [string]$Python='python', [switch]$Benchmark)
$ErrorActionPreference='Stop'
Set-Location -LiteralPath (Split-Path $PSScriptRoot -Parent)
$adb=Join-Path $env:LOCALAPPDATA 'Android/Sdk/platform-tools/adb.exe'
function Adb {
    $result=& $adb -s $Serial @args
    if ($LASTEXITCODE -ne 0) { throw "ADB failed: $args" }
    return $result
}
function PythonStep([string[]]$Arguments) {
    & $Python @Arguments
    if ($LASTEXITCODE -ne 0) { throw "Python step failed: $Arguments" }
}
if ((Adb get-state) -ne 'device') { throw 'Connect the requested Android device first' }
if (!(Test-Path .models/speaker-refinement.onnx)) { throw 'Run scripts/setup-engine.ps1 first' }
if (!(Test-Path .models/ami-four-speakers.wav)) { PythonStep @('scripts/prepare-ami-corpus.py') }
PythonStep @('scripts/prepare-ami-corpus.py','--start','49','--end','79','--stem','ami-development','--from-clip','.models/ami-four-speakers.wav')
PythonStep @('scripts/prepare-ami-corpus.py','--start','79','--end','110','--stem','ami-holdout','--from-clip','.models/ami-four-speakers.wav')
$annotationArchive='.deps/ami-public-manual-1.6.2.zip'
if (!(Test-Path $annotationArchive)) {
    & curl.exe -L --fail --retry 2 --silent --show-error https://groups.inf.ed.ac.uk/ami/AMICorpusAnnotations/ami_public_manual_1.6.2.zip -o $annotationArchive
    if ($LASTEXITCODE -ne 0) { throw 'Official AMI annotation download failed' }
}
if ((Get-FileHash $annotationArchive -Algorithm SHA256).Hash.ToLower() -ne 'b56e5babb2496b8795deeeda7e71178d7fbc9963f94276cf2a3f4b56ebbc9f9d') { throw 'AMI archive checksum mismatch' }
PythonStep @('scripts/prepare-ami-word-references.py')
& ./gradlew.bat testDebugUnitTest assembleDebug assembleDebugAndroidTest lintDebug --console=plain
if ($LASTEXITCODE -ne 0) { throw 'Build, unit tests or Lint failed' }
Adb push app/build/outputs/apk/debug/app-debug.apk /data/local/tmp/replika-voice-v4.apk
$installed=Adb shell pm install -r /data/local/tmp/replika-voice-v4.apk
if (($installed -join "`n") -notmatch 'Success') { throw "APK install failed: $installed" }
Adb shell rm /data/local/tmp/replika-voice-v4.apk
Adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
Adb shell run-as team.replika mkdir -p files
$asr='.models/sherpa-onnx-small-zipformer-ru-2024-09-18/test_wavs'
& ffmpeg -hide_banner -loglevel error -y -stream_loop 4 -i "$asr/1.wav" -ar 44100 -ac 2 .models/window-test.mp3
if ($LASTEXITCODE -ne 0) { throw 'ffmpeg fixture generation failed' }
$fixtures=@{
    "$asr/0.wav"='test.wav'; "$asr/1.wav"='test1.wav'
    '.models/window-test.mp3'='window-test.mp3'; '.models/two-speakers.wav'='two-speakers.wav'
    '.models/ami-development.wav'='ami-development.wav'; '.models/ami-holdout.wav'='ami-holdout.wav'
    'artifacts/test-results/corpus/ami-development-words.json'='ami-development-words.json'
}
foreach ($entry in $fixtures.GetEnumerator()) {
    Adb push $entry.Key "/data/local/tmp/replika-$($entry.Value)"
    Adb shell run-as team.replika cp "/data/local/tmp/replika-$($entry.Value)" "files/$($entry.Value)"
}
$classes='team.replika.SpeakerRefinementTest,team.replika.SpeakerAnchorTest,team.replika.ParallelDiarizationTest,team.replika.VoiceContinuityTest,team.replika.EditorUiTest,team.replika.SpeakerAnchorUiTest'
if ($Benchmark) { $classes+=',team.replika.PhysicalValidationTest#annotatedMeetingDiarization,team.replika.DiarizationOptimizationTest,team.replika.DiarizationWindowTest' }
$output=Adb shell am instrument -w -e class $classes -e amiValidation true -e optimizationBenchmark true -e windowBenchmark true team.replika.test/androidx.test.runner.AndroidJUnitRunner
New-Item -ItemType Directory -Force artifacts/test-results/voice-v4 | Out-Null
$output | Set-Content artifacts/test-results/voice-v4/android.txt -Encoding utf8NoBOM
$output
if (($output -join "`n") -notmatch 'OK \(\d+ tests\)') { throw 'Voice v4 Android checks failed; inspect android.txt' }
$proofs=@('speaker-refinement-proof.json','speaker-anchor-proof.json','speaker-anchor-ui-proof.json','parallel-diarization-proof.json','voice-continuity.json','voice-recalculation.json')
if ($Benchmark) { $proofs+=@('ami-validation.json','diarization-optimization.json','diarization-window-benchmark.json') }
foreach ($name in $proofs) {
    Adb exec-out run-as team.replika cat "files/$name" | Set-Content "artifacts/test-results/voice-v4/$name" -Encoding utf8NoBOM
}
