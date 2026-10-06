@echo off
REM ============================================================================
REM  Double-click this on the VDI (Windows) to build the self-contained app.
REM
REM  It just runs package-windows.ps1 sitting next to it, which extracts the fat
REM  JAR, trains a CDS archive and calls jpackage, producing:
REM       dist\MQ-mebaysanization-<version>-win.zip
REM  Unzip that anywhere and double-click the .exe inside.
REM
REM  Needs: JDK 21 on PATH (or JAVA_HOME set), and the fat JAR + package-windows.ps1
REM  in this same folder. Pass extra options straight through, e.g.:
REM       build-windows-app.bat -Type msi
REM ============================================================================
setlocal
cd /d "%~dp0"

powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0package-windows.ps1" %*
set "RC=%ERRORLEVEL%"

echo.
echo ============================================================
if "%RC%"=="0" (
  echo  DONE. Output is in the  dist\  folder next to this file.
) else (
  echo  FAILED (exit %RC%). Read the messages above -- usually JDK 21
  echo  is missing from PATH, or the JAR is not next to this file.
)
echo ============================================================
echo.
pause
