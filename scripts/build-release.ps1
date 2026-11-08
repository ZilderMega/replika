# One persistent local signing identity. Never commit or upload its private files.
param([string]$SigningDirectory = (Join-Path $env:USERPROFILE '.android/replika-release'))
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
Set-Location $root
if (!$env:JAVA_HOME) { $env:JAVA_HOME = 'C:/Program Files/Android/Android Studio/jbr' }
if (!(Test-Path (Join-Path $env:JAVA_HOME 'bin/java.exe'))) { throw 'Set JAVA_HOME to a JDK 17+ installation' }
$env:PATH = (Join-Path $env:JAVA_HOME 'bin') + ';' + $env:PATH
New-Item -ItemType Directory -Force -Path $SigningDirectory | Out-Null
$configFile = Join-Path $SigningDirectory 'signing.json'
if (!(Test-Path $configFile)) {
    if (Test-Path (Join-Path $SigningDirectory 'replika-release.jks')) { throw 'Existing key without config: restore signing.json rather than replacing the key' }
    $password = [Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(36))
    $config = @{ storeFile = (Join-Path $SigningDirectory 'replika-release.jks'); password = $password; alias = 'replika' }
    $config | ConvertTo-Json | Set-Content -LiteralPath $configFile -Encoding utf8
} else { $config = Get-Content -LiteralPath $configFile -Raw | ConvertFrom-Json -AsHashtable }
$env:REPLIKA_STORE_FILE = $config.storeFile
$env:REPLIKA_STORE_PASSWORD = $config.password
$env:REPLIKA_KEY_ALIAS = $config.alias
$env:REPLIKA_KEY_PASSWORD = $config.password
try {
    if (!(Test-Path $config.storeFile)) {
        & keytool -genkeypair -keystore $config.storeFile -storepass:env REPLIKA_STORE_PASSWORD -keypass:env REPLIKA_KEY_PASSWORD -alias $config.alias -keyalg RSA -keysize 3072 -validity 10000 -dname 'CN=Replika, OU=Android, O=Replika'
        if ($LASTEXITCODE -ne 0) { throw 'Signing key generation failed' }
    }
    & ./gradlew.bat :app:assembleRelease :app:testReleaseUnitTest :app:lintRelease
    if ($LASTEXITCODE -ne 0) { throw 'Release build/checks failed' }
    Copy-Item -LiteralPath app/build/outputs/apk/release/app-release.apk -Destination artifacts/replika-0.6.0-release.apk -Force
    $buildTools = Join-Path $env:LOCALAPPDATA 'Android/Sdk/build-tools/35.0.0'
    & (Join-Path $buildTools 'apksigner.bat') verify --verbose --print-certs artifacts/replika-0.6.0-release.apk | Set-Content -LiteralPath artifacts/test-results/submission-release-signature.txt -Encoding utf8
    if ($LASTEXITCODE -ne 0) { throw 'Release signature verification failed' }
    & (Join-Path $buildTools 'aapt.exe') dump badging artifacts/replika-0.6.0-release.apk | Set-Content -LiteralPath artifacts/test-results/submission-release-badging.txt -Encoding utf8
    if ($LASTEXITCODE -ne 0) { throw 'Release APK inspection failed' }
    Get-FileHash -LiteralPath artifacts/replika-0.6.0-release.apk -Algorithm SHA256
} finally {
    'REPLIKA_STORE_FILE','REPLIKA_STORE_PASSWORD','REPLIKA_KEY_ALIAS','REPLIKA_KEY_PASSWORD' | ForEach-Object { Remove-Item -LiteralPath "Env:$_" -ErrorAction SilentlyContinue }
}
