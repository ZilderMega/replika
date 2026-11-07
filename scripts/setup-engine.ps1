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
New-Item -ItemType Directory -Force .deps/ort/include | Out-Null
Download 'https://raw.githubusercontent.com/microsoft/onnxruntime/v1.17.1/include/onnxruntime/core/session/onnxruntime_c_api.h' '.deps/ort/include/onnxruntime_c_api.h' '7e47eb78563da119f740dc0ddfda96800e779e0bcbd169a9a49e9c10746c4cd5'
# Link to the same ORT shipped by sherpa, avoiding a second runtime in the APK.
Add-Type -AssemblyName System.IO.Compression.FileSystem
$archive = [System.IO.Compression.ZipFile]::OpenRead((Join-Path $root 'app/libs/sherpa-onnx-2.0.1.aar'))
try {
    foreach ($abi in @('arm64-v8a', 'x86_64')) {
        New-Item -ItemType Directory -Force ".deps/ort/$abi" | Out-Null
        [System.IO.Compression.ZipFileExtensions]::ExtractToFile($archive.GetEntry("jni/$abi/libonnxruntime.so"), (Join-Path $root ".deps/ort/$abi/libonnxruntime.so"), $true)
    }
} finally { $archive.Dispose() }
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
Download 'https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-recongition-models/wespeaker_en_voxceleb_resnet34.onnx' '.models/speaker-refinement.onnx' '5ef208a9da1453335308a6b6f4e6dfbd7e183a38b604de0a57664f45d257fe94'
Download 'https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/qwen2.5-0.5b-instruct-q4_k_m.gguf' '.models/llm.gguf' '74a4da8c9fdbcd15bd1f6d01d621410d31c6fc00986f5eb687824e7b93d7a9db'
$punctRevision = 'c4a35be7e4a413cdfb08427db75b621b571569c9'
Download "https://huggingface.co/ekhodzitsky/rupunct-small-onnx/resolve/$punctRevision/rupunct_small_int8.onnx" '.models/punctuation.onnx' 'b105da023474d98aa13ba18953ae67b04b17bd0595034bc06030c17536893933'
Download "https://huggingface.co/ekhodzitsky/rupunct-small-onnx/resolve/$punctRevision/tokenizer.json" '.models/punctuation-tokenizer.json' '7ca617388c2092a3a84272025c52bbf3c6db0aee225c0351186295c0b5d3ddc6'
Download "https://huggingface.co/ekhodzitsky/rupunct-small-onnx/resolve/$punctRevision/config.json" '.models/punctuation-config.json' '6924a8cf41ec2bd3a3aa73a387ae0ccd0aed253ec7cac4d2f53c7d27440891eb'
Download 'https://github.com/k2-fsa/sherpa-onnx/releases/download/speech-enhancement-models/gtcrn_simple.onnx' '.models/denoise.onnx' 'e77603ac0c23dac3227dd2d7135b3a585cbee2679048aecfa886657d3ae1b534'
Download 'https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad_v5.onnx' '.models/vad.onnx' '6b99cbfd39246b6706f98ec13c7c50c6b299181f2474fa05cbc8046acc274396'
Download 'https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-whisper-tiny.tar.bz2' '.deps/whisper-tiny.tar.bz2' 'c46116994e539aa165266d96b325252728429c12535eb9d8b6a2b10f129e66b1'
Download 'https://huggingface.co/welcomyou/convtasnet-libri2mix-16k-onnx/resolve/da50e0fa7789356790994bc898290134fef5d42d/convtasnet_16k.onnx' '.models/separation.onnx' '22185d8e13bf5251c0eeab09e52099ac76c063cd9a5e5df1f5c242f535f6f151'
& tar -xf .deps/whisper-tiny.tar.bz2 -C .models
if ($LASTEXITCODE -ne 0) { throw 'Whisper archive extraction failed' }
Copy-Item '.models/sherpa-onnx-whisper-tiny/tiny-encoder.int8.onnx' .models/whisper-encoder.onnx -Force
Copy-Item '.models/sherpa-onnx-whisper-tiny/tiny-decoder.int8.onnx' .models/whisper-decoder.onnx -Force
Copy-Item '.models/sherpa-onnx-whisper-tiny/tiny-tokens.txt' .models/whisper-tokens.txt -Force
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
$names = @('encoder.onnx', 'decoder.onnx', 'joiner.onnx', 'tokens.txt', 'segmentation.onnx', 'embedding.onnx', 'llm.gguf', 'punctuation.onnx', 'punctuation-tokenizer.json', 'punctuation-config.json', 'denoise.onnx', 'vad.onnx', 'whisper-encoder.onnx', 'whisper-decoder.onnx', 'whisper-tokens.txt', 'separation.onnx')
$hashes = [ordered]@{}
foreach ($name in $names) { $hashes[$name] = (Get-FileHash ".models/$name" -Algorithm SHA256).Hash.ToLowerInvariant() }
@{schema=1; profile='zipformer-ru-small-2024-09-18_pyannote3_campplus_qwen2.5-0.5b'; sha256=$hashes} |
    ConvertTo-Json -Depth 4 | Set-Content .models/manifest.json -Encoding utf8NoBOM
Write-Output 'Models ready in .models. Gradle embeds them in the APK automatically; no user import is required.'
