@echo off
setlocal EnableExtensions DisableDelayedExpansion
cd /d "%~dp0"

set "PAYLOAD=%~dp0payload\minion-0.1.0.jar"
set "PAYLOAD_HASH=%~dp0payload\minion-0.1.0.jar.sha256"
set "TARGET_DIR="

title Minion V35 cumulative update
echo ========================================
echo Minion V35 cumulative update for Win7
echo Includes V33 and later stop/UI fixes
echo ========================================
echo.

if not exist "%PAYLOAD%" goto :missing_payload
if not exist "%PAYLOAD_HASH%" goto :missing_payload

rem Win7 certutil may print the SHA-256 bytes separated by spaces. Read the
rem entire hash line, then remove spaces before comparing with the sidecar.
set "EXPECTED_HASH="
for /f "usebackq tokens=1" %%H in ("%PAYLOAD_HASH%") do if not defined EXPECTED_HASH set "EXPECTED_HASH=%%H"
set "ACTUAL_HASH="
for /f "skip=1 tokens=*" %%H in ('certutil -hashfile "%PAYLOAD%" SHA256 2^>nul') do if not defined ACTUAL_HASH set "ACTUAL_HASH=%%H"
set "ACTUAL_HASH=%ACTUAL_HASH: =%"
if not defined EXPECTED_HASH goto :bad_hash
if /I not "%ACTUAL_HASH%"=="%EXPECTED_HASH%" goto :bad_hash
echo Package SHA-256 verified successfully.
if /I "%~1"=="/verify" goto :verified
echo.

if exist "%~dp0minion-0.1.0.jar" set "TARGET_DIR=%~dp0"
if not defined TARGET_DIR if exist "%~dp0..\minion-0.1.0.jar" set "TARGET_DIR=%~dp0.."

:check_target
if defined TARGET_DIR if exist "%TARGET_DIR%\minion-0.1.0.jar" goto :target_ok
echo Enter the Minion installation directory containing minion-0.1.0.jar.
echo Example: D:\ai_agent\minion\minion-win7-offline-0.1.0
set "TARGET_DIR="
set /p "TARGET_DIR=Installation directory: "
if not defined TARGET_DIR goto :cancelled
set "TARGET_DIR=%TARGET_DIR:"=%"
goto :check_target

:target_ok
for %%I in ("%TARGET_DIR%") do set "TARGET_DIR=%%~fI"
echo Installation: %TARGET_DIR%
echo Projects, model settings, skills, chat history and knowledge paths are not deleted.
echo The installed JAR will be backed up before replacement.
echo.
choice /C YN /N /M "Start update? [Y/N]: "
if errorlevel 2 goto :cancelled

call :stop_minion
if not exist "%TARGET_DIR%\backup" mkdir "%TARGET_DIR%\backup"
if errorlevel 1 goto :failed
:choose_backup
set "RECOVERY_BACKUP=%TARGET_DIR%\backup\minion-0.1.0.jar.pre-v35-%RANDOM%.bak"
if exist "%RECOVERY_BACKUP%" goto :choose_backup
copy /B "%TARGET_DIR%\minion-0.1.0.jar" "%RECOVERY_BACKUP%" >nul
if errorlevel 1 goto :failed

if exist "%TARGET_DIR%\minion-0.1.0.jar.new" del /Q "%TARGET_DIR%\minion-0.1.0.jar.new" >nul 2>nul
if exist "%TARGET_DIR%\minion-0.1.0.jar.new" goto :failed
copy /B "%PAYLOAD%" "%TARGET_DIR%\minion-0.1.0.jar.new" >nul
if errorlevel 1 goto :failed

set "COPIED_HASH="
for /f "skip=1 tokens=*" %%H in ('certutil -hashfile "%TARGET_DIR%\minion-0.1.0.jar.new" SHA256 2^>nul') do if not defined COPIED_HASH set "COPIED_HASH=%%H"
set "COPIED_HASH=%COPIED_HASH: =%"
if /I not "%COPIED_HASH%"=="%EXPECTED_HASH%" goto :failed

move /Y "%TARGET_DIR%\minion-0.1.0.jar.new" "%TARGET_DIR%\minion-0.1.0.jar" >nul
if errorlevel 1 goto :failed

echo.
echo V35 update completed successfully.
echo Previous JAR backup: %RECOVERY_BACKUP%
echo Start Minion normally.
pause
exit /b 0

:verified
echo Verification only: no installed files were changed.
pause
exit /b 0

:bad_hash
echo ERROR: Package SHA-256 check failed. Nothing was changed.
echo Expected: %EXPECTED_HASH%
echo Actual:   %ACTUAL_HASH%
echo Extract the whole ZIP to a normal folder and run this script again.
pause
exit /b 1

:failed
if exist "%TARGET_DIR%\minion-0.1.0.jar.new" del /Q "%TARGET_DIR%\minion-0.1.0.jar.new" >nul 2>nul
if defined RECOVERY_BACKUP if exist "%RECOVERY_BACKUP%" copy /B /Y "%RECOVERY_BACKUP%" "%TARGET_DIR%\minion-0.1.0.jar" >nul 2>nul
echo ERROR: Update failed. The backup was restored if available.
echo Backup: %RECOVERY_BACKUP%
pause
exit /b 1

:missing_payload
echo ERROR: Update payload is incomplete. Extract the whole ZIP first.
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

