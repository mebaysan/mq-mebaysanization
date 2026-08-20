# Packages the pre-built fat JAR into a self-contained Windows app with jpackage.
#
# WHY THIS EXISTS
#   jpackage bundles a Java runtime into the output, so the target Windows machine (e.g. the VDI) needs
#   no separate Java install. jpackage only ever builds for the OS it runs on, so a Windows .exe must be
#   produced ON Windows — run this on the VDI (or any Windows box with a JDK 21).
#
# WHAT YOU NEED ON THE WINDOWS MACHINE
#   - JDK 21 (the same one that has jpackage). Set JAVA_HOME or put its \bin on PATH.
#   - The fat JAR, built elsewhere with `mvn clean package` and copied next to this script:
#         mq-mebaysanization-<version>.jar
#   - Maven and Node are NOT needed here; the JAR is already the whole app (UI included).
#   - For -Type app-image: nothing else. For -Type msi: the WiX Toolset must be installed.
#
# LICENCE NOTE (read once)
#   The JAR nests IBM MQ's restricted client materials. Packaging it into an .exe for your own internal
#   use (the VDI) is fine — it is the same as building the JAR yourself. Do NOT publish or hand out that
#   .exe outside your organisation: that would redistribute IBM's client.
#
# USAGE
#   powershell -ExecutionPolicy Bypass -File .\package-windows.ps1
#   powershell -ExecutionPolicy Bypass -File .\package-windows.ps1 -Type msi
#
# RESULT
#   dist\MQ mebaysanization\   (app-image: a portable folder — zip it, drop it on any Windows box)
#     MQ mebaysanization.exe    <- double-click; a console opens, the server starts on :8080
#   Then open http://localhost:8080 in a browser.

param(
  [string]$Jar = "",
  [ValidateSet("app-image", "msi", "exe")]
  [string]$Type = "app-image",
  # H2 stores connection profiles + the AES key here. Must be WRITABLE by the user running the app —
  # never a read-only Program Files path. ProgramData is the usual choice for a shared, writable spot.
  [string]$DataDir = "C:\ProgramData\MQmebaysanization\data",
  # The 8-bit app icon (.ico). Defaults to the one committed next to this script. Regenerate or tweak it
  # with `python scripts\make-icon.py`. Pass "" to build with jpackage's default icon.
  [string]$Icon = (Join-Path $PSScriptRoot "app-icon.ico")
)

$ErrorActionPreference = "Stop"
$AppName = "MQ mebaysanization"
$AppVersion = "1.1.5"

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

# jpackage wants a directory holding the input; give it one containing only the JAR.
$InputDir = Join-Path $PSScriptRoot "jpackage-input"
if (Test-Path $InputDir) { Remove-Item $InputDir -Recurse -Force }
New-Item -ItemType Directory -Force -Path $InputDir | Out-Null
Copy-Item $Jar (Join-Path $InputDir (Split-Path $Jar -Leaf)) -Force

$DestDir = Join-Path $PSScriptRoot "dist"
if (Test-Path $DestDir) { Remove-Item $DestDir -Recurse -Force }

$jpackageArgs = @(
  "--type", $Type,
  "--name", $AppName,
  "--app-version", $AppVersion,
  "--input", $InputDir,
  "--main-jar", (Split-Path $Jar -Leaf),
  # The fat-JAR manifest already names this, but passing it explicitly avoids version-to-version guesswork.
  "--main-class", "org.springframework.boot.loader.launch.JarLauncher",
  # Keep the H2 store off the (possibly read-only) install directory. Spring reads this system property
  # for `${MQMANAGER_DATA_DIR}` exactly as it reads the env var.
  "--java-options", "-DMQMANAGER_DATA_DIR=$DataDir",
  "--java-options", "-Xmx512m",
  # A console window so the server's log is visible and closing it stops the server.
  "--win-console",
  "--dest", $DestDir
)

# Attach the custom 8-bit icon when present.
if (-not [string]::IsNullOrEmpty($Icon) -and (Test-Path $Icon)) {
  $jpackageArgs += @("--icon", $Icon)
  Write-Host "Using icon: $Icon"
} else {
  Write-Host "No .ico found — building with jpackage's default icon."
}

Write-Host "Running jpackage ($Type)..."
& jpackage @jpackageArgs

Write-Host ""
Write-Host "Done. Output in: $DestDir"
Write-Host "Data directory (connections + AES key) will live in: $DataDir"
Write-Host "Launch the app, then open http://localhost:8080"
