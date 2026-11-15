@echo off
setlocal EnableExtensions DisableDelayedExpansion
cd /d "%~dp0"

set "PATCH=%~dp0payload\classes-patch.zip"
set "PATCH_HASH=%~dp0payload\classes-patch.zip.sha256"
set "BASE_HASH=%~dp0payload\base-jar.sha256"
set "PATCHER=%~dp0payload\JarPatchApplier.class"
set "PATCHER_HASH=%~dp0payload\JarPatchApplier.class.sha256"
set "TARGET_DIR="

title Minion V37 small update from V36
echo ========================================
echo Minion V37 small update from exact V36
echo ========================================
echo.

if not exist "%PATCH%" goto :missing_payload
if not exist "%PATCH_HASH%" goto :missing_payload
if not exist "%BASE_HASH%" goto :missing_payload
if not exist "%PATCHER%" goto :missing_payload
if not exist "%PATCHER_HASH%" goto :missing_payload
call :check_hash "%PATCH%" "%PATCH_HASH%"
if errorlevel 1 goto :bad_hash
call :check_hash "%PATCHER%" "%PATCHER_HASH%"
if errorlevel 1 goto :bad_hash
echo Package SHA-256 checks passed.
if /I "%~1"=="/verify" goto :verified

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
call :check_hash "%TARGET_DIR%\minion-0.1.0.jar" "%BASE_HASH%"
if errorlevel 1 goto :wrong_version
set "JAVA_EXE=%TARGET_DIR%\jdk8\bin\java.exe"
if not exist "%JAVA_EXE%" set "JAVA_EXE=%TARGET_DIR%\jre\bin\java.exe"
if not exist "%JAVA_EXE%" set "JAVA_EXE=java.exe"
"%JAVA_EXE%" -version >nul 2>nul
if errorlevel 1 goto :no_java
if exist "%TARGET_DIR%\minion-0.1.0.jar.new" goto :staged_exists

echo Installation: %TARGET_DIR%
echo Exact V36 JAR verified. Only 1 changed class will be patched.
echo Settings, projects and history are not changed.
echo.
choice /C YN /N /M "Start update? [Y/N]: "
if errorlevel 2 goto :cancelled

call :stop_minion
call :check_hash "%TARGET_DIR%\minion-0.1.0.jar" "%BASE_HASH%"
if errorlevel 1 goto :wrong_version
if not exist "%TARGET_DIR%\backup" mkdir "%TARGET_DIR%\backup"
if errorlevel 1 goto :failed
:choose_backup
set "RECOVERY_BACKUP=%TARGET_DIR%\backup\minion-0.1.0.jar.pre-v37-small-%RANDOM%.bak"
if exist "%RECOVERY_BACKUP%" goto :choose_backup
copy /B "%TARGET_DIR%\minion-0.1.0.jar" "%RECOVERY_BACKUP%" >nul
if errorlevel 1 goto :failed

"%JAVA_EXE%" -cp "%~dp0payload" JarPatchApplier "%TARGET_DIR%\minion-0.1.0.jar" "%PATCH%" "%TARGET_DIR%\minion-0.1.0.jar.new"
if errorlevel 1 goto :failed
if not exist "%TARGET_DIR%\minion-0.1.0.jar.new" goto :failed
move /Y "%TARGET_DIR%\minion-0.1.0.jar.new" "%TARGET_DIR%\minion-0.1.0.jar" >nul
if errorlevel 1 goto :failed

echo.
echo V37 small update completed successfully.
echo Previous JAR backup: %RECOVERY_BACKUP%
echo Start Minion normally.
pause
exit /b 0

:check_hash
set "EXPECTED_HASH="
for /f "usebackq tokens=1" %%H in ("%~2") do if not defined EXPECTED_HASH set "EXPECTED_HASH=%%H"
set "ACTUAL_HASH="
for /f "skip=1 tokens=*" %%H in ('certutil -hashfile "%~1" SHA256 2^>nul') do if not defined ACTUAL_HASH set "ACTUAL_HASH=%%H"
set "ACTUAL_HASH=%ACTUAL_HASH: =%"
if not defined EXPECTED_HASH exit /b 1
if /I not "%ACTUAL_HASH%"=="%EXPECTED_HASH%" exit /b 1
exit /b 0

:verified
echo Verification only: no installed files were changed.
pause
exit /b 0

:wrong_version
echo ERROR: Installed JAR does not match the exact V36 base.
echo Nothing was changed. Please use a matching full update; do not force this patch.
pause
exit /b 1

:bad_hash
echo ERROR: Small update payload SHA-256 check failed. Nothing was changed.
echo Extract the whole ZIP to a normal folder and try again.
pause
exit /b 1

:no_java
echo ERROR: Java runtime not found. Nothing was changed.
pause
exit /b 1

:staged_exists
echo ERROR: minion-0.1.0.jar.new already exists in the install directory.
echo Move that file aside only after checking it. Nothing was changed.
pause
exit /b 1

:failed
if exist "%TARGET_DIR%\minion-0.1.0.jar.new" del /Q "%TARGET_DIR%\minion-0.1.0.jar.new" >nul 2>nul
if defined RECOVERY_BACKUP if exist "%RECOVERY_BACKUP%" copy /B /Y "%RECOVERY_BACKUP%" "%TARGET_DIR%\minion-0.1.0.jar" >nul 2>nul
echo ERROR: Small update failed. The previous JAR was restored if available.
echo Backup: %RECOVERY_BACKUP%
pause
exit /b 1

:missing_payload
echo ERROR: Small update payload is incomplete. Extract the whole ZIP first.
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
