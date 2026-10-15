@echo off
rem Run only the integration tests (classes matching *IntegrationTest).
rem They exercise the real `git` binary — git must be on PATH.
setlocal
cd /d "%~dp0"

if not defined JAVA_HOME (
    rem Fall back to IntelliJ IDEA's bundled runtime if present.
    for /d %%D in ("%ProgramFiles%\JetBrains\IntelliJ IDEA*") do set "JAVA_HOME=%%D\jbr"
)
if defined JAVA_HOME set "PATH=%JAVA_HOME%\bin;%PATH%"

call gradlew.bat test --rerun --tests "*IntegrationTest" %* || exit /b 1
endlocal
