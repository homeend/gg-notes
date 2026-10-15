@echo off
rem Build the distributable GgNotes plugin zip.
rem Needs a JDK 17-23 (NOT 25) — set JAVA_HOME below or in the environment.
setlocal

if not defined JAVA_HOME (
    rem Fall back to IntelliJ IDEA's bundled runtime if present — adjust the path to your install.
    for /d %%D in ("%ProgramFiles%\JetBrains\IntelliJ IDEA*") do set "JAVA_HOME=%%D\jbr"
)
if not defined JAVA_HOME (
    echo ERROR: JAVA_HOME is not set and no IntelliJ IDEA install was found.
    echo Set JAVA_HOME to a JDK 17-23 and re-run.
    exit /b 1
)
set "PATH=%JAVA_HOME%\bin;%PATH%"
echo Using JAVA_HOME=%JAVA_HOME%

rem drop stale zips so the printed result is unambiguously THIS build
del /q build\distributions\*.zip 2>nul

call gradlew.bat buildPlugin || exit /b 1

echo.
echo Distribution ready:
dir /b build\distributions\*.zip
echo Full path: %~dp0build\distributions\
echo Install via: Settings ^> Plugins ^> gear icon ^> "Install Plugin from Disk..."
endlocal
