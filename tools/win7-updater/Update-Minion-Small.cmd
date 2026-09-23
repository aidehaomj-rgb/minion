@echo off
setlocal EnableExtensions DisableDelayedExpansion
cd /d "%~dp0"

set "PATCH=%~dp0payload\classes-patch.zip"
set "PATCH_HASH=%~dp0payload\classes-patch.zip.sha256"
set "BASE_HASH=%~dp0payload\base-jar.sha256"
set "PATCHER=%~dp0payload\JarPatchApplier.class"
set "SAVED_DIR=%~dp0install-dir.txt"
set "TARGET_DIR="

title Minion Small Updater
echo ========================================
echo Minion Win7 incremental updater
echo ========================================
echo.

if not exist "%PATCH%" goto :missing_payload
if not exist "%PATCH_HASH%" goto :missing_payload
if not exist "%BASE_HASH%" goto :missing_payload
if not exist "%PATCHER%" goto :missing_payload

if exist "%~dp0minion-0.1.0.jar" set "TARGET_DIR=%~dp0"
if not defined TARGET_DIR if exist "%~dp0..\minion-0.1.0.jar" set "TARGET_DIR=%~dp0.."
if not defined TARGET_DIR if exist "%SAVED_DIR%" set /p TARGET_DIR=<"%SAVED_DIR%"

:check_target
if defined TARGET_DIR if exist "%TARGET_DIR%\minion-0.1.0.jar" goto :target_ok
echo Minion installation directory was not found automatically.
echo Example: D:\ai_agent\minion\minion-win7-offline-0.1.0
set "TARGET_DIR="
set /p "TARGET_DIR=Enter Minion installation directory: "
if not defined TARGET_DIR goto :cancelled
set "TARGET_DIR=%TARGET_DIR:"=%"
goto :check_target

:target_ok
for %%I in ("%TARGET_DIR%") do set "TARGET_DIR=%%~fI"
>"%SAVED_DIR%" echo %TARGET_DIR%
set "JAVA_EXE=%TARGET_DIR%\jdk8\bin\java.exe"
if not exist "%JAVA_EXE%" set "JAVA_EXE=%TARGET_DIR%\jre\bin\java.exe"
if not exist "%JAVA_EXE%" set "JAVA_EXE=java.exe"

set "EXPECTED_PATCH="
for /f "usebackq tokens=1" %%H in ("%PATCH_HASH%") do if not defined EXPECTED_PATCH set "EXPECTED_PATCH=%%H"
set "ACTUAL_PATCH="
for /f "skip=1 tokens=*" %%H in ('certutil -hashfile "%PATCH%" SHA256 2^>nul') do if not defined ACTUAL_PATCH set "ACTUAL_PATCH=%%H"
set "ACTUAL_PATCH=%ACTUAL_PATCH: =%"
if /I not "%ACTUAL_PATCH%"=="%EXPECTED_PATCH%" goto :bad_hash

set "EXPECTED_BASE="
for /f "usebackq tokens=1" %%H in ("%BASE_HASH%") do if not defined EXPECTED_BASE set "EXPECTED_BASE=%%H"
if /I "%EXPECTED_BASE%"=="ANY" goto :base_ok
set "ACTUAL_BASE="
for /f "skip=1 tokens=*" %%H in ('certutil -hashfile "%TARGET_DIR%\minion-0.1.0.jar" SHA256 2^>nul') do if not defined ACTUAL_BASE set "ACTUAL_BASE=%%H"
set "ACTUAL_BASE=%ACTUAL_BASE: =%"
if /I not "%ACTUAL_BASE%"=="%EXPECTED_BASE%" goto :wrong_version

:base_ok

echo Installation: %TARGET_DIR%
echo Patch size is only changed Java classes.
echo.
choice /C YN /N /M "Start update? [Y/N]: "
if errorlevel 2 goto :cancelled

call :stop_minion
if exist "%TARGET_DIR%\minion-0.1.0.jar.new" del /Q "%TARGET_DIR%\minion-0.1.0.jar.new" >nul 2>nul
if not exist "%TARGET_DIR%\backup" mkdir "%TARGET_DIR%\backup"
copy /Y "%TARGET_DIR%\minion-0.1.0.jar" "%TARGET_DIR%\backup\minion-0.1.0.jar.bak" >nul
if errorlevel 1 goto :failed

"%JAVA_EXE%" -cp "%~dp0payload" JarPatchApplier "%TARGET_DIR%\minion-0.1.0.jar" "%PATCH%" "%TARGET_DIR%\minion-0.1.0.jar.new"
if errorlevel 1 goto :failed
move /Y "%TARGET_DIR%\minion-0.1.0.jar.new" "%TARGET_DIR%\minion-0.1.0.jar" >nul
if errorlevel 1 goto :failed

echo.
echo Incremental update completed successfully.
echo Backup: %TARGET_DIR%\backup\minion-0.1.0.jar.bak
pause
exit /b 0

:wrong_version
echo ERROR: Installed Minion version does not match this incremental package.
echo Install the previous SFM sessionId package first, or request a full update package.
pause
exit /b 1

:bad_hash
echo ERROR: Incremental package SHA-256 verification failed. Nothing was changed.
pause
exit /b 1

:failed
if exist "%TARGET_DIR%\minion-0.1.0.jar.new" del /Q "%TARGET_DIR%\minion-0.1.0.jar.new" >nul 2>nul
if exist "%TARGET_DIR%\backup\minion-0.1.0.jar.bak" copy /Y "%TARGET_DIR%\backup\minion-0.1.0.jar.bak" "%TARGET_DIR%\minion-0.1.0.jar" >nul 2>nul
echo ERROR: Incremental update failed. The previous JAR was restored.
pause
exit /b 1

:missing_payload
echo ERROR: Incremental update payload is incomplete.
pause
exit /b 1

:cancelled
echo Update cancelled. Nothing was changed.
pause
exit /b 2

:stop_minion
where wmic >nul 2>nul
if errorlevel 1 goto :stop_wait
wmic process where "name='java.exe' and commandline like '%%minion-0.1.0.jar%%'" call terminate >nul 2>nul
wmic process where "name='javaw.exe' and commandline like '%%minion-0.1.0.jar%%'" call terminate >nul 2>nul
:stop_wait
ping 127.0.0.1 -n 3 >nul
exit /b 0
