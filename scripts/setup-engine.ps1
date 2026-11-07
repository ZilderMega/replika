param([switch]$SkipModels)
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
Set-Location -LiteralPath $root
New-Item -ItemType Directory -Force .deps, .models, app/libs, artifacts | Out-Null

function Download([string]$Url, [string]$Destination, [string]$Sha256 = '') {
    if (Test-Path -LiteralPath $Destination) {
        if (!$Sha256 -or (Get-FileHash -LiteralPath $Destination -Algorithm SHA256).Hash -eq $Sha256) { return }
    }
    & curl.exe -L --fail --retry 3 --silent --show-error -o "$Destination.part" $Url
    if ($LASTEXITCODE -ne 0) { throw "Download failed: $Url" }
    if ($Sha256 -and (Get-FileHash -LiteralPath "$Destination.part" -Algorithm SHA256).Hash -ne $Sha256) { throw "Checksum mismatch: $Url" }
    Move-Item -LiteralPath "$Destination.part" -Destination $Destination -Force
}

Download 'https://github.com/k2-fsa/sherpa-onnx/releases/download/v2.0.1/sherpa-onnx-2.0.1.aar' 'app/libs/sherpa-onnx-2.0.1.aar' 'a8d09d1d32f3eb8520482c6d3d43fae674681c5b050385967c70a3c2c9bf0ee7'
$revision = '0a683e8088d849626e7471f9e2ed381f7dbdf2e9'
if (!(Test-Path .deps/llama.cpp/.git)) {
    & git clone --depth 1 --branch b3998 https://github.com/ggml-org/llama.cpp.git .deps/llama.cpp
    if ($LASTEXITCODE -ne 0) { throw 'llama.cpp clone failed' }
}
if ((& git -C .deps/llama.cpp rev-parse HEAD) -ne $revision) { throw 'Unexpected llama.cpp revision' }
if ($SkipModels) { return }

Download 'https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-small-zipformer-ru-2024-09-18.tar.bz2' '.deps/asr.tar.bz2' 'f766cea7df7e7204a5eac9edfba2d05e7546523a88e803f4a110b1865358d51a'
Download 'https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-segmentation-models/sherpa-onnx-pyannote-segmentation-3-0.tar.bz2' '.deps/seg.tar.bz2' '24615ee884c897d9d2ba09bb4d30da6bb1b15e685065962db5b02e76e4996488'
Download 'https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-recongition-models/3dspeaker_speech_campplus_sv_en_voxceleb_16k.onnx' '.models/embedding.onnx' '357a834f702b80161e5b981182c038e18553c1f2ca752ed6cec2052365d4129b'
Download 'https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/qwen2.5-0.5b-instruct-q4_k_m.gguf' '.models/llm.gguf' '74a4da8c9fdbcd15bd1f6d01d621410d31c6fc00986f5eb687824e7b93d7a9db'
Download 'https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-segmentation-models/1-two-speakers-en.wav' '.models/two-speakers.wav' 'f1c877dc01595e28be7147bf2fe38e5268147a868bf3fdb5c37b97f5940e21f3'
& tar -xf .deps/asr.tar.bz2 -C .models
if ($LASTEXITCODE -ne 0) { throw 'ASR archive extraction failed' }
& tar -xf .deps/seg.tar.bz2 -C .models
if ($LASTEXITCODE -ne 0) { throw 'Segmentation archive extraction failed' }
$asr = '.models/sherpa-onnx-small-zipformer-ru-2024-09-18'
Copy-Item "$asr/encoder.int8.onnx" .models/encoder.onnx -Force
Copy-Item "$asr/decoder.onnx" .models/decoder.onnx -Force
Copy-Item "$asr/joiner.int8.onnx" .models/joiner.onnx -Force
Copy-Item "$asr/tokens.txt" .models/tokens.txt -Force
Copy-Item '.models/sherpa-onnx-pyannote-segmentation-3-0/model.int8.onnx' .models/segmentation.onnx -Force
$names = @('encoder.onnx', 'decoder.onnx', 'joiner.onnx', 'tokens.txt', 'segmentation.onnx', 'embedding.onnx', 'llm.gguf')
$hashes = [ordered]@{}
foreach ($name in $names) { $hashes[$name] = (Get-FileHash ".models/$name" -Algorithm SHA256).Hash.ToLowerInvariant() }
@{schema=1; profile='zipformer-ru-small-2024-09-18_pyannote3_campplus_qwen2.5-0.5b'; sha256=$hashes} |
    ConvertTo-Json -Depth 4 | Set-Content .models/manifest.json -Encoding utf8NoBOM
Write-Output 'Models ready in .models. Gradle embeds them in the APK automatically; no user import is required.'
