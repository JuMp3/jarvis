# Builds the backend the desktop app ships, in src-tauri/resources/backend: the Spring Boot jar and a Java runtime
# reduced with jlink. Run it after changing the backend, before `npm run tauri dev` or `npm run tauri build`.
#
#   powershell -ExecutionPolicy Bypass -File prepare-backend.ps1 [-JavaHome C:\path\to\jdk-25]
param([string]$JavaHome = $env:JAVA_HOME)

$ErrorActionPreference = 'Stop'
if (-not $JavaHome -or -not (Test-Path "$JavaHome/bin/jlink.exe")) {
    throw "Serve un JDK 25: passa -JavaHome oppure imposta JAVA_HOME"
}
$root = Resolve-Path "$PSScriptRoot/.."
$out = "$PSScriptRoot/src-tauri/resources/backend"

# From jdeps --print-module-deps on the jar, plus what is only loaded at runtime: locale data (Italian dates),
# JNDI (Tomcat), extra charsets, zip file systems, and the Windows certificate store (jdk.crypto.mscapi): jlink
# takes cacerts from the jmods, so a CA added to the JDK is lost, while the shell makes Java trust the store
# where IT installs corporate CAs (TLS inspection). Recompute with jdeps when dependencies change.
$modules = 'java.base,java.compiler,java.desktop,java.instrument,java.net.http,java.prefs,java.scripting,' +
        'java.security.jgss,java.sql.rowset,jdk.jfr,jdk.management,jdk.unsupported,' +
        'jdk.localedata,java.naming,jdk.charsets,jdk.zipfs,jdk.crypto.mscapi'

$env:JAVA_HOME = $JavaHome
& "$root/mvnw.cmd" -q -f "$root/pom.xml" package -DskipTests
if ($LASTEXITCODE -ne 0) { throw "Build del backend non riuscita" }

if (Test-Path $out) { Remove-Item -Recurse -Force $out }
New-Item -ItemType Directory $out | Out-Null
$jar = Get-ChildItem "$root/target/jarvis-*.jar" | Where-Object { $_.Name -notlike '*-plain.jar' } | Select-Object -First 1
Copy-Item $jar.FullName "$out/jarvis.jar"

& "$JavaHome/bin/jlink" --add-modules $modules --strip-debug --no-header-files --no-man-pages `
        --compress=zip-6 --output "$out/runtime"
if ($LASTEXITCODE -ne 0) { throw "jlink non riuscito" }

$size = (Get-ChildItem -Recurse $out | Measure-Object -Property Length -Sum).Sum / 1MB
Write-Host ("Backend pronto in {0} ({1:N0} MB)" -f $out, $size)
