# Prepares the PostgreSQL the desktop app ships, in src-tauri/resources/postgres: the official Windows binaries (EDB)
# reduced to what runs the server, plus pgvector built from source with Visual Studio (C++ build tools).
# Downloads are cached in .cache. Run it once, and again only to change version.
#
#   powershell -ExecutionPolicy Bypass -File prepare-postgres.ps1
param(
    [string]$PostgresVersion = '18.4-1',
    [string]$PgvectorVersion = '0.8.7',
    [string]$VcVars = 'C:/Program Files (x86)/Microsoft Visual Studio/2022/BuildTools/VC/Auxiliary/Build/vcvars64.bat'
)

$ErrorActionPreference = 'Stop'
$cache = "$PSScriptRoot/.cache"
$out = "$PSScriptRoot/src-tauri/resources/postgres"
New-Item -ItemType Directory -Force $cache | Out-Null

function Download($url, $file) {
    if (Test-Path $file) { return }
    # --ssl-no-revoke: behind TLS inspection (Netskope) Windows cannot reach the revocation lists; the certificate
    # chain is still verified
    & curl.exe --ssl-no-revoke -fsSL -o "$file.part" $url
    if ($LASTEXITCODE -ne 0) { throw "Download non riuscito: $url" }
    Move-Item "$file.part" $file
}

# PostgreSQL: a fresh copy of the binaries, where pgvector is installed
$zip = "$cache/postgresql-$PostgresVersion-windows-x64-binaries.zip"
Download "https://get.enterprisedb.com/postgresql/postgresql-$PostgresVersion-windows-x64-binaries.zip" $zip
$pg = "$cache/pgsql"
if (Test-Path $pg) { Remove-Item -Recurse -Force $pg }
# Windows' tar (bsdtar) reads zip and extracts only what is needed: the zip has 22,000 files, mostly pgAdmin, and
# Expand-Archive takes many minutes on it
& "$env:SystemRoot/System32/tar.exe" -xf $zip -C $cache pgsql/bin pgsql/lib pgsql/share pgsql/include pgsql/server_license.txt
if ($LASTEXITCODE -ne 0) { throw "Estrazione di PostgreSQL non riuscita" }

# pgvector, built against those binaries (Makefile.win needs PGROOT, with include and lib)
$vectorZip = "$cache/pgvector-$PgvectorVersion.zip"
Download "https://github.com/pgvector/pgvector/archive/refs/tags/v$PgvectorVersion.zip" $vectorZip
$vector = "$cache/pgvector-$PgvectorVersion"
if (Test-Path $vector) { Remove-Item -Recurse -Force $vector }
& "$env:SystemRoot/System32/tar.exe" -xf $vectorZip -C $cache
if ($LASTEXITCODE -ne 0) { throw "Estrazione di pgvector non riuscita" }
if (-not (Test-Path $VcVars)) { throw "Servono gli strumenti C++ di Visual Studio: $VcVars non esiste" }
$build = "call `"$VcVars`" >nul && cd /d `"$vector`" && set `"PGROOT=$pg`" && nmake /NOLOGO /F Makefile.win && nmake /NOLOGO /F Makefile.win install"
& cmd.exe /c $build
if ($LASTEXITCODE -ne 0) { throw "Build di pgvector non riuscita" }

# Only what runs the server: no pgAdmin, StackBuilder, headers, documentation, debug symbols
if (Test-Path $out) { Remove-Item -Recurse -Force $out }
New-Item -ItemType Directory $out | Out-Null
foreach ($dir in 'bin', 'lib', 'share') {
    Copy-Item -Recurse "$pg/$dir" "$out/$dir"
}
Copy-Item "$pg/server_license.txt", "$vector/LICENSE" "$out" -ErrorAction SilentlyContinue
Rename-Item "$out/LICENSE" 'pgvector_license.txt' -ErrorAction SilentlyContinue
Get-ChildItem -Recurse $out -Include *.pdb, *.lib | Remove-Item -Force
# StackBuilder and its wxWidgets libraries are a GUI installer, not part of the server
Get-ChildItem "$out/bin" -Include stackbuilder.exe, wx*.dll -Recurse | Remove-Item -Force
# Server messages: English is built in, Italian is kept; the other translations are most of share (Jarvis shows
# its own messages, not the server's: add a language here only if needed)
Get-ChildItem "$out/share/locale" -Directory | Where-Object { $_.Name -ne 'it' } | Remove-Item -Recurse -Force

# The Visual C++ runtime the binaries need: many PCs have it, a clean one does not. App-local copy, as the
# Visual Studio redistributable allows
$crt = Get-ChildItem (Join-Path (Split-Path $VcVars) '../../Redist/MSVC') -Recurse -Directory -Filter 'Microsoft.VC143.CRT' |
        Where-Object { $_.FullName -match '\\x64\\' -and $_.FullName -notmatch 'onecore' } | Select-Object -First 1
if (-not $crt) { throw "Runtime di Visual C++ (Microsoft.VC143.CRT, x64) non trovato accanto a $VcVars" }
Copy-Item "$($crt.FullName)/*.dll" "$out/bin"

$size = (Get-ChildItem -Recurse $out | Measure-Object -Property Length -Sum).Sum / 1MB
Write-Host ("PostgreSQL $PostgresVersion con pgvector $PgvectorVersion pronto in {0} ({1:N0} MB)" -f $out, $size)
