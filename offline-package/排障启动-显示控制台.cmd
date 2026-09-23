@echo off
setlocal
cd /d "%~dp0"
set "MINION_JAVA=%CD%\jdk8\bin\java.exe"

if not exist "%MINION_JAVA%" (
  echo [ERROR] Bundled JDK 8 was not found: %MINION_JAVA%
  pause
  exit /b 1
)

"%MINION_JAVA%" -Dfile.encoding=UTF-8 -Dprism.order=es2,sw -jar "%CD%\minion-0.1.0.jar"
echo.
echo Minion exited with code %ERRORLEVEL%.
pause
endlocal
