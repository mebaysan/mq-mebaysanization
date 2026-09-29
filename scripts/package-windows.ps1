# Packages the pre-built fat JAR into a self-contained Windows app with jpackage.
#
# WHY THIS EXISTS
#   jpackage bundles a Java runtime into the output, so the target Windows machine (e.g. the VDI) needs
#   no separate Java install. jpackage only ever builds for the OS it runs on, so a Windows .exe must be
#   produced ON Windows - run this on the VDI (or any Windows box with a JDK 21).
#
# WHY THE APP USED TO TAKE OVER A MINUTE TO START ON A VDI  (LOAD-BEARING)
#   The fat JAR is ~100 MiB and nests every dependency (incl. IBM MQ) inside one archive. On a locked-down
#   VDI that archive usually lives on a network/roaming-profile drive, and each launch paid three taxes at
#   once: the whole 100 MiB was streamed over the network, the antivirus re-scanned every nested jar entry
#   as classes were touched, and Spring Boot's loader seek-hunted inside a single 100 MiB file for every
#   class. This script removes all three by shipping the OFFICIAL Spring Boot fast-start layout instead of
#   the raw fat JAR:
#     1. EXTRACT  - `java -Djarmode=tools ... extract` explodes the fat JAR into a thin launcher jar plus a
#        lib/ folder of ordinary jars. The AV scans each jar once (and caches it), and the JVM memory-maps
#        individual jars instead of trawling one giant archive.
#     2. CDS      - a one-off "training run" records the classes loaded during a normal startup into an
#        application.jsa archive. At runtime the JVM maps that archive instead of parsing class bytecode
#        again, which is the bulk of the remaining startup cost. See "Class Data Sharing" in the Spring
#        Boot reference. If the archive ever fails to match (e.g. JDK changed) the JVM silently falls back
#        to a normal start - never a crash - so this is safe even when it does not engage.
#     3. FLAGS    - a few startup-biased JVM flags (single-tier JIT, serial GC, no JMX export) shave the
#        rest. They trade a little steady-state throughput for a much faster start, which is the right call
#        for a low-traffic admin tool whose real work is broker/network I/O, not CPU.
#   Net effect on a VDI: from >60 s to a few seconds. Extraction alone is the dominant win and is
#   guaranteed; CDS and the flags are best-effort bonuses on top.
#
# WHAT YOU NEED ON THE WINDOWS MACHINE
#   - JDK 21 (the same one that has jpackage). Set JAVA_HOME or put its \bin on PATH.
#     The SAME JDK 21 builds the CDS archive AND is bundled into the app, so the two always match - a
#     mismatch is the one thing that quietly disables CDS.
#   - The fat JAR, built elsewhere with `mvn clean package` and copied next to this script:
#         mq-mebaysanization-<version>.jar
#   - Maven and Node are NOT needed here; the JAR is already the whole app (UI included).
#   - For -Type app-image: nothing else. For -Type msi: the WiX Toolset must be installed.
#
# LICENCE NOTE (read once)
#   The JAR nests IBM MQ's restricted client materials. Packaging it into an .exe for your own internal
#   use (the VDI) is fine - it is the same as building the JAR yourself. Do NOT publish or hand out that
#   .exe outside your organisation: that would redistribute IBM's client.
#
# USAGE
#   powershell -ExecutionPolicy Bypass -File .\package-windows.ps1
#   powershell -ExecutionPolicy Bypass -File .\package-windows.ps1 -Type msi
#
# RESULT
#   dist\MQ-mebaysanization-<version>-win.zip   (app-image: ONE file - copy it to the VDI as-is)
#     Unzip anywhere on the VDI, then double-click  MQ mebaysanization\BASLAT.bat
#     (a launcher written into the image: it puts the data dir under %LOCALAPPDATA% so a locked-down VDI
#     cannot deny it, and keeps the console open so a startup error is readable. The .exe beside it works
#     too.) A console opens and the server starts on :48080.
#   Then open http://localhost:48080 in a browser. (Override the port with -DMQMANAGER_PORT.)

param(
  [string]$Jar = "",
  [ValidateSet("app-image", "msi", "exe")]
  [string]$Type = "app-image",
  # NOTE: the data directory is no longer baked in here. BASLAT.bat (written into the image) points it at
  # %LOCALAPPDATA%\MQmebaysanization\data, which is writable on a locked-down VDI; a bare .exe launch
  # falls back to a `data` folder beside the .exe. See the --java-options block below.
  # The 8-bit app icon (.ico). Defaults to the one committed next to this script. Regenerate or tweak it
  # with `python scripts\make-icon.py`. Pass "" to build with jpackage's default icon.
  [string]$Icon = (Join-Path $PSScriptRoot "app-icon.ico")
)

$ErrorActionPreference = "Stop"
$AppName = "MQ mebaysanization"
# Derived from the JAR's own filename below, so it never drifts from the released version. This is only
# a fallback for the unusual case where the name does not parse.
$AppVersion = "1.0.0"

# Locate the JAR if not given explicitly.
if ([string]::IsNullOrEmpty($Jar)) {
  $Jar = (Get-ChildItem -Path . -Filter "mq-mebaysanization-*.jar" |
          Where-Object { $_.Name -notlike "*original*" } |
          Select-Object -First 1).FullName
}
if ([string]::IsNullOrEmpty($Jar) -or -not (Test-Path $Jar)) {
  throw "Fat JAR not found. Copy mq-mebaysanization-<version>.jar next to this script, or pass -Jar <path>."
}
Write-Host "Using JAR: $Jar"

# Take the app version from the JAR's filename (mq-mebaysanization-<version>.jar), so the .exe and the
# output zip are labelled with whatever was actually built rather than a number hand-edited here.
if ((Split-Path $Jar -Leaf) -match 'mq-mebaysanization-(.+?)(?:-original)?\.jar$') {
  $AppVersion = $Matches[1]
}
Write-Host "App version: $AppVersion"

# jpackage wants a directory holding the input. Rather than drop the raw fat JAR in, we EXTRACT it into
# the Spring Boot fast-start layout (thin launcher jar + lib/) and then TRAIN a CDS archive against it.
# jpackage copies every file under --input (subdirectories included) into the app image, so the lib/
# folder and the .jsa travel with the .exe automatically.
$JarLeaf = Split-Path $Jar -Leaf
$InputDir = Join-Path $PSScriptRoot "jpackage-input"
if (Test-Path $InputDir) { Remove-Item $InputDir -Recurse -Force }

# 1) EXTRACT. `jarmode=tools extract` writes <InputDir>/<jar> (thin launcher, Main-Class still JarLauncher)
#    plus <InputDir>/lib/*.jar. --destination creates the folder, so we must NOT pre-create it.
Write-Host "Extracting fat JAR into fast-start layout..."
& java "-Djarmode=tools" -jar $Jar extract --destination $InputDir
if ($LASTEXITCODE -ne 0) { throw "jarmode extract failed (exit $LASTEXITCODE)." }

# 2) TRAIN the CDS archive. `spring.context.exit=onRefresh` starts the app far enough to load every class
#    it needs, then exits cleanly BEFORE binding the port or touching any broker - so this needs no network
#    and no free port. ArchiveClassesAtExit writes the archive on that clean exit. The training run creates
#    an H2 db + encryption key; we send those to a throwaway dir and delete it so they never ship.
$Jsa = "application.jsa"
$TrainData = Join-Path $env:TEMP ("mqm-cds-train-" + [System.Guid]::NewGuid().ToString("N"))
Write-Host "Training CDS archive (one-off; app starts and exits automatically)..."
Push-Location $InputDir
try {
  & java "-XX:ArchiveClassesAtExit=$Jsa" "-Dspring.context.exit=onRefresh" `
         "-DMQMANAGER_DATA_DIR=$TrainData" -jar $JarLeaf
  # A non-zero exit or a missing .jsa is not fatal: without CDS the app still starts, just a bit slower.
  if (-not (Test-Path $Jsa)) {
    Write-Warning "CDS archive was not produced; continuing without it (startup will be a little slower)."
  }
} finally {
  Pop-Location
  if (Test-Path $TrainData) { Remove-Item $TrainData -Recurse -Force -ErrorAction SilentlyContinue }
}

$DestDir = Join-Path $PSScriptRoot "dist"
if (Test-Path $DestDir) { Remove-Item $DestDir -Recurse -Force }

$jpackageArgs = @(
  "--type", $Type,
  "--name", $AppName,
  "--app-version", $AppVersion,
  "--input", $InputDir,
  "--main-jar", $JarLeaf,
  # EXTRACTED layout, not the fat JAR: `jarmode extract` rewrites the launcher jar so its Main-Class is
  # the application class directly (there is no BOOT-INF and no JarLauncher any more), and its manifest
  # Class-Path lists lib/*.jar. jpackage launches it as `-cp <jar> <main-class>`, and the JVM honours the
  # jar's Class-Path header on the classpath, so lib/ is picked up. Passing JarLauncher here would fail.
  "--main-class", "com.baysansoft.mqmanager.MqManagerApplication",
  # NO baked -DMQMANAGER_DATA_DIR here (on purpose). A hard-coded C:\ProgramData path is the usual reason
  # a double-clicked .exe "does nothing" on a locked-down VDI: the user cannot create that folder, H2 and
  # Flyway fail on startup, and the console flashes shut before the error can be read. Instead the data
  # directory is chosen at launch: BASLAT.bat (written into the image below) points it at a per-user
  # %LOCALAPPDATA% folder that is always writable, and a bare double-click of the .exe falls back to a
  # `data` folder next to the .exe. Either way there is nothing under ProgramData to be denied.
  "--java-options", "-Xmx512m",
  # Double-clicking an icon should land the user on the app, so open their browser once it is serving.
  # Off by default in the app; every desktop launcher turns it on. Close the console window to stop.
  "--java-options", "-Dmqmanager.open-browser=true",
  # Map the CDS archive at startup. $APPDIR is substituted by the jpackage launcher to the app image's
  # app folder at run time (the leading ` escapes it from PowerShell so jpackage receives it verbatim).
  # -Xshare stays at its default (auto), so a mismatched/absent archive downgrades to a normal start
  # rather than failing hard.
  "--java-options", "-XX:SharedArchiveFile=`$APPDIR\$Jsa",
  # Startup-biased flags: stop the JIT at C1 (skip the slow C2 warm-up), use the single-threaded serial
  # GC (no concurrent GC threads to spin up on a small VDI), and skip JMX MBean export. All three trade a
  # little steady-state throughput for a faster start - the right trade for an I/O-bound admin tool.
  "--java-options", "-XX:TieredStopAtLevel=1",
  "--java-options", "-XX:+UseSerialGC",
  "--java-options", "-Dspring.jmx.enabled=false",
  # A console window so the server's log is visible and closing it stops the server.
  "--win-console",
  "--dest", $DestDir
)

# Attach the custom 8-bit icon when present.
if (-not [string]::IsNullOrEmpty($Icon) -and (Test-Path $Icon)) {
  $jpackageArgs += @("--icon", $Icon)
  Write-Host "Using icon: $Icon"
} else {
  Write-Host "No .ico found - building with jpackage's default icon."
}

Write-Host "Running jpackage ($Type)..."
& jpackage @jpackageArgs
if ($LASTEXITCODE -ne 0) { throw "jpackage failed (exit $LASTEXITCODE)." }

# For app-image, jpackage leaves a folder ("dist\MQ mebaysanization\") - not something you can hand off as
# one file. Zip it so there is a single artefact to copy onto the VDI: unzip anywhere, double-click the
# .exe. (msi/exe types are already a single installer file, so there is nothing to zip.)
$Artifact = $DestDir
if ($Type -eq "app-image") {
  $AppFolder = Join-Path $DestDir $AppName

  # A double-click launcher next to the .exe. Two things a bare .exe double-click cannot do on a VDI:
  #   1. put the H2 store somewhere always writable (%LOCALAPPDATA%), sidestepping the ProgramData
  #      permission wall that makes the .exe exit instantly; and
  #   2. keep the console open after the app stops, so a startup error is readable instead of flashing by.
  # Written as ASCII (cmd reads batch files in the OEM code page) with no non-ASCII characters.
  $launcher = @"
@echo off
REM MQ mebaysanization - cift tikla. Veriyi kullaniciya yazilabilir bir klasorde tutar (kilitli VDI'da
REM ProgramData izin sorunu olmaz) ve hata olursa konsol acik kalir.
setlocal
set "MQMANAGER_DATA_DIR=%LOCALAPPDATA%\MQmebaysanization\data"
if not exist "%MQMANAGER_DATA_DIR%" mkdir "%MQMANAGER_DATA_DIR%" 2>nul
"%~dp0$AppName.exe" %*
echo.
echo ============================================================
echo  Uygulama kapandi. Yukarida hata varsa oku.
echo  Veri klasoru: %MQMANAGER_DATA_DIR%
echo ============================================================
pause
"@
  Set-Content -Path (Join-Path $AppFolder "BASLAT.bat") -Value $launcher -Encoding Ascii

  $Zip = Join-Path $DestDir ("MQ-mebaysanization-" + $AppVersion + "-win.zip")
  Write-Host "Zipping app-image into a single file for the VDI..."
  # -Force overwrites a stale zip from a previous run; the .jsa/lib/ folder are inside $AppFolder already.
  Compress-Archive -Path $AppFolder -DestinationPath $Zip -Force
  $Artifact = $Zip
}

Write-Host ""
Write-Host "Done."
if ($Type -eq "app-image") {
  Write-Host "Copy this one file to the VDI and unzip it anywhere:"
  Write-Host "  $Artifact"
  Write-Host "Then double-click BASLAT.bat inside (recommended - writable data dir, keeps errors on screen)."
  Write-Host "The .exe next to it also works; BASLAT.bat just makes it robust on a locked-down VDI."
} else {
  Write-Host "Installer: $Artifact"
}
Write-Host "Data (connections + AES key): %LOCALAPPDATA%\MQmebaysanization\data via BASLAT.bat,"
Write-Host "or a 'data' folder beside the .exe if you launch it directly."
Write-Host "After launch, open http://localhost:48080"
