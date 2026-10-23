# Prepares the local voice, in src-tauri/resources/voice: the server of whisper.cpp (speech-to-text) and Piper
# (text-to-speech), with their models. Jarvis runs them itself (LocalVoice); the desktop app ships this folder, and
# in development JARVIS_VOICE_HOME points to it. Downloads are cached in .cache.
#
#   powershell -ExecutionPolicy Bypass -File prepare-voice.ps1
param(
    [string]$WhisperBuild = 'b5454',
    [string]$PiperVersion = '2023.11.14-2',
    # Piper voices, one per language of jarvis.voice.local.tts-voices
    [string[]]$Voices = @('it/it_IT/paola/medium/it_IT-paola-medium', 'en/en_US/lessac/medium/en_US-lessac-medium')
)

$ErrorActionPreference = 'Stop'
$cache = "$PSScriptRoot/.cache"
$out = "$PSScriptRoot/src-tauri/resources/voice"
$tar = "$env:SystemRoot/System32/tar.exe"
New-Item -ItemType Directory -Force $cache | Out-Null

function Download($url, $file) {
    if (Test-Path $file) { return }
    # --ssl-no-revoke: behind TLS inspection (Netskope) Windows cannot reach the revocation lists; the certificate
    # chain is still verified
    & curl.exe --ssl-no-revoke -fsSL -o "$file.part" $url
    if ($LASTEXITCODE -ne 0) { throw "Download non riuscito: $url" }
    Move-Item "$file.part" $file
}

$huggingFace = 'https://huggingface.co'
$whisperZip = "$cache/whisper-$WhisperBuild-bin-x64.zip"
$piperZip = "$cache/piper-$PiperVersion-windows-amd64.zip"
Download "https://github.com/ggml-org/whisper.cpp/releases/download/$WhisperBuild/whisper-bin-x64.zip" $whisperZip
Download "https://github.com/rhasspy/piper/releases/download/$PiperVersion/piper_windows_amd64.zip" $piperZip
# Whisper small quantized (more precise, the default) and base (faster): Settings → Voce chooses
foreach ($model in 'ggml-small-q5_1.bin', 'ggml-base.bin') {
    Download "$huggingFace/ggerganov/whisper.cpp/resolve/main/$model" "$cache/$model"
}
$voiceFiles = foreach ($voice in $Voices) {
    $voiceName = Split-Path $voice -Leaf
    foreach ($extension in '.onnx', '.onnx.json') {
        Download "$huggingFace/rhasspy/piper-voices/resolve/main/$voice$extension" "$cache/$voiceName$extension"
        "$cache/$voiceName$extension"
    }
}

if (Test-Path $out) { Remove-Item -Recurse -Force $out }
New-Item -ItemType Directory "$out/whisper", "$out/models" | Out-Null

# whisper.cpp: only the server and its libraries (the ggml-cpu-* ones are picked at runtime for the processor)
$unpacked = "$cache/whisper-$WhisperBuild"
if (Test-Path $unpacked) { Remove-Item -Recurse -Force $unpacked }
New-Item -ItemType Directory $unpacked | Out-Null
& $tar -xf $whisperZip -C $unpacked
if ($LASTEXITCODE -ne 0) { throw "Estrazione di whisper.cpp non riuscita" }
Copy-Item "$unpacked/Release/whisper-server.exe", "$unpacked/Release/whisper.dll", "$unpacked/Release/ggml*.dll" "$out/whisper"

# Piper: the whole package (espeak-ng-data is needed for the phonemes)
& $tar -xf $piperZip -C $out
if ($LASTEXITCODE -ne 0) { throw "Estrazione di Piper non riuscita" }

Copy-Item (@("$cache/ggml-small-q5_1.bin", "$cache/ggml-base.bin") + $voiceFiles) "$out/models"

$size = (Get-ChildItem -Recurse $out | Measure-Object -Property Length -Sum).Sum / 1MB
Write-Host ("Voce locale pronta in {0} ({1:N0} MB)" -f $out, $size)
