@echo off
setlocal
cd /d "%~dp0"
set "MINION_JAVA=%CD%\jdk8\bin\java.exe"

if not exist "%CD%\jdk8\bin\javaw.exe" (
  echo [ERROR] Bundled JDK 8 was not found: %CD%\jdk8\bin\javaw.exe
  pause
  exit /b 1
)

start "Minion" "%CD%\jdk8\bin\javaw.exe" -Dfile.encoding=UTF-8 -Dprism.order=es2,sw -jar "%CD%\minion-0.1.0.jar"
endlocal
