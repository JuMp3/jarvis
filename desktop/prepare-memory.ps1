# Prepares the model of the long-term memory, in src-tauri/resources/memory: multilingual-e5-small (ONNX, quantized),
# the embedding model Jarvis runs itself (MemoryConfig). The desktop app ships this folder; in development
# JARVIS_MEMORY_HOME points to it. Downloads are cached in .cache.
#
#   powershell -ExecutionPolicy Bypass -File prepare-memory.ps1
param([string]$Model = 'Xenova/multilingual-e5-small')

$ErrorActionPreference = 'Stop'
$cache = "$PSScriptRoot/.cache/$($Model -replace '/', '-')"
$out = "$PSScriptRoot/src-tauri/resources/memory"
New-Item -ItemType Directory -Force $cache | Out-Null

# --ssl-no-revoke: behind TLS inspection (Netskope) Windows cannot reach the revocation lists; the certificate chain
# is still verified
foreach ($file in 'onnx/model_quantized.onnx', 'tokenizer.json') {
    $target = "$cache/$(Split-Path $file -Leaf)"
    if (Test-Path $target) { continue }
    & curl.exe --ssl-no-revoke -fsSL -o "$target.part" "https://huggingface.co/$Model/resolve/main/$file"
    if ($LASTEXITCODE -ne 0) { throw "Download non riuscito: $file" }
    Move-Item "$target.part" $target
}

if (Test-Path $out) { Remove-Item -Recurse -Force $out }
New-Item -ItemType Directory $out | Out-Null
Copy-Item "$cache/model_quantized.onnx" "$out/model.onnx"
Copy-Item "$cache/tokenizer.json" "$out/tokenizer.json"
Write-Host ("Modello della memoria pronto in {0} ({1:N0} MB)" -f $out, ((Get-ChildItem $out | Measure-Object Length -Sum).Sum / 1MB))
