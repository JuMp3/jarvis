# Prepares the Node the desktop app ships, in src-tauri/resources/node: node.exe and, already installed, the npx MCP
# servers the Settings can configure (today the Outlook / Microsoft 365 one). Jarvis runs them with it instead of npx
# (NpxCommand), so the PC needs no Node. The package version is read from MailTemplates.java, so the two cannot differ.
# Run it on the system the app is built for: native modules (keytar) are installed for it. Downloads cached in .cache.
#
#   powershell -ExecutionPolicy Bypass -File prepare-node.ps1
param([string]$NodeVersion = '24.22.1')

$ErrorActionPreference = 'Stop'
$cache = "$PSScriptRoot/.cache"
$out = "$PSScriptRoot/src-tauri/resources/node"
$tar = "$env:SystemRoot/System32/tar.exe"
New-Item -ItemType Directory -Force $cache | Out-Null

$templates = Get-Content "$PSScriptRoot/../src/main/java/dev/jarvis/settings/MailTemplates.java" -Raw
if ($templates -notmatch 'MS365_PACKAGE = "([^"]+)"') { throw "Versione del pacchetto MCP non trovata in MailTemplates.java" }
$package = $Matches[1]

$name = "node-v$NodeVersion-win-x64"
$zip = "$cache/$name.zip"
if (-not (Test-Path $zip)) {
    # --ssl-no-revoke: behind TLS inspection (Netskope) Windows cannot reach the revocation lists
    & curl.exe --ssl-no-revoke -fsSL -o "$zip.part" "https://nodejs.org/dist/v$NodeVersion/$name.zip"
    if ($LASTEXITCODE -ne 0) { throw "Download di Node non riuscito" }
    Move-Item "$zip.part" $zip
}
if (Test-Path "$cache/$name") { Remove-Item -Recurse -Force "$cache/$name" }
& $tar -xf $zip -C $cache "$name/node.exe" "$name/node_modules/npm"
if ($LASTEXITCODE -ne 0) { throw "Estrazione di Node non riuscita" }

if (Test-Path $out) { Remove-Item -Recurse -Force $out }
New-Item -ItemType Directory $out | Out-Null
Copy-Item "$cache/$name/node.exe" $out
# npm only to install: the app needs node.exe and the installed package. The system certificates also for npm.
$env:NODE_OPTIONS = '--use-system-ca'
& "$out/node.exe" "$cache/$name/node_modules/npm/bin/npm-cli.js" install --prefix $out --omit=dev --no-audit --no-fund $package
if ($LASTEXITCODE -ne 0) { throw "Installazione di $package non riuscita" }

$size = (Get-ChildItem -Recurse $out | Measure-Object -Property Length -Sum).Sum / 1MB
Write-Host ("Node $NodeVersion con $package pronto in {0} ({1:N0} MB)" -f $out, $size)
