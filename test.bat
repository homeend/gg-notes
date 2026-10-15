@echo off
rem Run the full GgNotes test suite (unit + integration).
rem Extra args pass through to Gradle, e.g.: test.bat --tests "dev.ggnotes.session.*"
setlocal
cd /d "%~dp0"

if not defined JAVA_HOME (
    rem Fall back to IntelliJ IDEA's bundled runtime if present.
    for /d %%D in ("%ProgramFiles%\JetBrains\IntelliJ IDEA*") do set "JAVA_HOME=%%D\jbr"
)
if defined JAVA_HOME set "PATH=%JAVA_HOME%\bin;%PATH%"

call gradlew.bat test --rerun %* || exit /b 1
endlocal
