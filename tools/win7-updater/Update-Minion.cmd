@echo off
setlocal EnableExtensions DisableDelayedExpansion
cd /d "%~dp0"

set "PAYLOAD=%~dp0payload\minion-0.1.0.jar"
set "HASH_FILE=%~dp0payload\minion-0.1.0.jar.sha256"
set "SAVED_DIR=%~dp0install-dir.txt"
set "TARGET_DIR="

title Minion Updater
echo ========================================
echo Minion offline updater
echo ========================================
echo.

if not exist "%PAYLOAD%" goto :missing_payload
if not exist "%HASH_FILE%" goto :missing_payload

rem 1. The update folder itself is the installation directory.
if exist "%~dp0minion-0.1.0.jar" set "TARGET_DIR=%~dp0"

rem 2. The update folder is directly inside the installation directory.
if not defined TARGET_DIR if exist "%~dp0..\minion-0.1.0.jar" set "TARGET_DIR=%~dp0.."

rem 3. Reuse the installation directory selected on the previous run.
if not defined TARGET_DIR if exist "%SAVED_DIR%" set /p TARGET_DIR=<"%SAVED_DIR%"

:check_target
if defined TARGET_DIR if exist "%TARGET_DIR%\minion-0.1.0.jar" goto :target_ok

echo Minion installation directory was not found automatically.
echo Example: D:\tools\minion-win7-offline-0.1.0
set "TARGET_DIR="
set /p "TARGET_DIR=Enter Minion installation directory: "
if not defined TARGET_DIR goto :cancelled
set "TARGET_DIR=%TARGET_DIR:"=%"
goto :check_target

:target_ok
for %%I in ("%TARGET_DIR%") do set "TARGET_DIR=%%~fI"
>"%SAVED_DIR%" echo %TARGET_DIR%

echo Installation: %TARGET_DIR%
echo Payload:      %PAYLOAD%
echo.
echo Minion will be closed automatically before files are replaced.
choice /C YN /N /M "Start update? [Y/N]: "
if errorlevel 2 goto :cancelled

set "EXPECTED="
for /f "usebackq tokens=1" %%H in ("%HASH_FILE%") do if not defined EXPECTED set "EXPECTED=%%H"
if not defined EXPECTED goto :bad_hash

set "ACTUAL="
for /f "skip=1 tokens=*" %%H in ('certutil -hashfile "%PAYLOAD%" SHA256 2^>nul') do if not defined ACTUAL set "ACTUAL=%%H"
set "ACTUAL=%ACTUAL: =%"
if /I not "%ACTUAL%"=="%EXPECTED%" goto :bad_hash

call :stop_minion

rem A failed previous run may have left a temporary file behind.
if exist "%TARGET_DIR%\minion-0.1.0.jar.new" del /Q "%TARGET_DIR%\minion-0.1.0.jar.new" >nul 2>nul
if exist "%TARGET_DIR%\minion-0.1.0.jar.new" goto :file_locked

if not exist "%TARGET_DIR%\backup" mkdir "%TARGET_DIR%\backup"
copy /Y "%TARGET_DIR%\minion-0.1.0.jar" "%TARGET_DIR%\backup\minion-0.1.0.jar.bak" >nul
if errorlevel 1 goto :backup_failed

copy /Y "%PAYLOAD%" "%TARGET_DIR%\minion-0.1.0.jar.new" >nul
if errorlevel 1 goto :replace_failed

set "COPIED_HASH="
for /f "skip=1 tokens=*" %%H in ('certutil -hashfile "%TARGET_DIR%\minion-0.1.0.jar.new" SHA256 2^>nul') do if not defined COPIED_HASH set "COPIED_HASH=%%H"
set "COPIED_HASH=%COPIED_HASH: =%"
if /I not "%COPIED_HASH%"=="%EXPECTED%" goto :replace_failed

move /Y "%TARGET_DIR%\minion-0.1.0.jar.new" "%TARGET_DIR%\minion-0.1.0.jar" >nul
if errorlevel 1 goto :replace_failed

echo.
echo Update completed successfully.
echo Backup: %TARGET_DIR%\backup\minion-0.1.0.jar.bak
echo You can now start Minion normally.
echo.
pause
exit /b 0

:replace_failed
if exist "%TARGET_DIR%\minion-0.1.0.jar.new" del /Q "%TARGET_DIR%\minion-0.1.0.jar.new" >nul 2>nul
set "RESTORED=0"
if exist "%TARGET_DIR%\backup\minion-0.1.0.jar.bak" copy /Y "%TARGET_DIR%\backup\minion-0.1.0.jar.bak" "%TARGET_DIR%\minion-0.1.0.jar" >nul 2>nul && set "RESTORED=1"
echo.
if "%RESTORED%"=="1" echo ERROR: Replacement failed. The previous JAR was restored.
if not "%RESTORED%"=="1" echo ERROR: Replacement failed and automatic restore also failed.
echo Close VS Code and any remaining Minion process, then run this updater as administrator.
pause
exit /b 1

:file_locked
echo.
echo ERROR: A Minion update file is still locked or cannot be deleted.
echo Close VS Code and Minion, then run this updater as administrator.
pause
exit /b 1

:backup_failed
echo.
echo ERROR: Could not create backup. Nothing was changed.
pause
exit /b 1

:bad_hash
echo.
echo ERROR: Update package SHA-256 verification failed. Nothing was changed.
pause
exit /b 1

:missing_payload
echo ERROR: payload\minion-0.1.0.jar or its SHA-256 file is missing.
pause
exit /b 1

:cancelled
echo.
echo Update cancelled. Nothing was changed.
pause
exit /b 2

:stop_minion
echo Closing running Minion process...
where wmic >nul 2>nul
if errorlevel 1 goto :stop_wait
wmic process where "name='java.exe' and commandline like '%%minion-0.1.0.jar%%'" call terminate >nul 2>nul
wmic process where "name='javaw.exe' and commandline like '%%minion-0.1.0.jar%%'" call terminate >nul 2>nul
:stop_wait
rem Win7 has ping even when timeout.exe is unavailable. This waits about two seconds for file handles to close.
ping 127.0.0.1 -n 3 >nul
exit /b 0
