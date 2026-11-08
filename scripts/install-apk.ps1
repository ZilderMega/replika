param([string]$Serial = '', [string]$Apk = '')
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$adb = Join-Path $env:LOCALAPPDATA 'Android/Sdk/platform-tools/adb.exe'
if (!$Apk) { $Apk = Join-Path $root 'artifacts/replika-0.6.0-release.apk' }
if (!(Test-Path -LiteralPath $adb)) { throw 'Android SDK platform-tools not found. Install them through Android Studio.' }
if (!$Serial) {
    $devices = @((& $adb devices) | Select-String '^emulator-\d+\s+device$' | ForEach-Object { ($_.Line -split '\s+')[0] })
    if ($devices.Count -ne 1) { throw 'Start one Android Studio emulator, or pass its ID using -Serial.' }
    $Serial = $devices[0]
}
if (!(Test-Path -LiteralPath $apk)) { $apk = Join-Path $root 'app/build/outputs/apk/release/app-release.apk' }
if (!(Test-Path -LiteralPath $apk)) { throw 'Release APK not found. Extract the submission archive or run scripts/build-release.ps1 first.' }
$state = & $adb -s $Serial get-state 2>$null
if ($state -ne 'device') { throw "Device $Serial is not ready. Start the Android Studio emulator, then repeat this command." }
& $adb -s $Serial install -r $apk
if ($LASTEXITCODE -ne 0) { throw 'APK installation failed. See the Android error above.' }
& $adb -s $Serial shell am start -n team.replika/.MainActivity
if ($LASTEXITCODE -ne 0) { throw 'Could not open Replika.' }
Write-Host 'Replika is open. Wait for model preparation, then select an audio or video file.'
