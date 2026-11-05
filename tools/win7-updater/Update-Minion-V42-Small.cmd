@echo off
setlocal EnableExtensions DisableDelayedExpansion
cd /d "%~dp0"

set "PAYLOAD=%~dp0payload"
set "PATCH=%PAYLOAD%\entries-patch.zip"
set "PATCHER=%PAYLOAD%\JarPatchApplier.class"
set "VERIFIER=%PAYLOAD%\JarEntryVerifier.class"
set "BASE_MANIFEST=%PAYLOAD%\v40-entries.tsv"
set "TARGET_MANIFEST=%PAYLOAD%\v42-entries.tsv"
set "TARGET_DIR="
set "STAGED_BY_US="

title Minion V42 small update from V40
echo ========================================
echo Minion V42 small update from V40
echo ========================================
echo.

for %%F in ("%PATCH%" "%PATCHER%" "%VERIFIER%" "%BASE_MANIFEST%" "%TARGET_MANIFEST%") do (
    if not exist "%%~fF" goto :missing_payload
    if not exist "%%~fF.sha256" goto :missing_payload
    call :check_hash "%%~fF" "%%~fF.sha256"
    if errorlevel 1 goto :bad_hash
)
echo Package SHA-256 checks passed.
if /I "%~1"=="/verify" goto :verified

if exist "%~dp0minion-0.1.0.jar" set "TARGET_DIR=%~dp0"
if not defined TARGET_DIR if exist "%~dp0..\minion-0.1.0.jar" set "TARGET_DIR=%~dp0.."
:check_target
if defined TARGET_DIR if exist "%TARGET_DIR%\minion-0.1.0.jar" goto :target_ok
echo Enter the Minion installation directory containing minion-0.1.0.jar.
set "TARGET_DIR="
set /p "TARGET_DIR=Installation directory: "
if not defined TARGET_DIR goto :cancelled
set "TARGET_DIR=%TARGET_DIR:"=%"
goto :check_target

:target_ok
for %%I in ("%TARGET_DIR%") do set "TARGET_DIR=%%~fI"
set "JAVA_EXE=%TARGET_DIR%\jdk8\bin\java.exe"
if not exist "%JAVA_EXE%" set "JAVA_EXE=%TARGET_DIR%\jre\bin\java.exe"
if not exist "%JAVA_EXE%" set "JAVA_EXE=java.exe"
"%JAVA_EXE%" -version >nul 2>nul
if errorlevel 1 goto :no_java
if exist "%TARGET_DIR%\minion-0.1.0.jar.new" goto :staged_exists
"%JAVA_EXE%" -cp "%PAYLOAD%" JarEntryVerifier verify "%TARGET_DIR%\minion-0.1.0.jar" "%BASE_MANIFEST%"
if errorlevel 1 goto :wrong_version

echo.
echo V40 content verified: only Minion JAR entries will be patched.
echo Projects, model settings, chat history and knowledge paths stay unchanged.
echo Close Minion before proceeding. A JAR backup will be created.
choice /C YN /N /M "Start update? [Y/N]: "
if errorlevel 2 goto :cancelled

"%JAVA_EXE%" -cp "%PAYLOAD%" JarEntryVerifier verify "%TARGET_DIR%\minion-0.1.0.jar" "%BASE_MANIFEST%"
if errorlevel 1 goto :wrong_version
if not exist "%TARGET_DIR%\backup" mkdir "%TARGET_DIR%\backup"
if errorlevel 1 goto :failed
:choose_backup
set "RECOVERY_BACKUP=%TARGET_DIR%\backup\minion-0.1.0.jar.pre-v42-small-%RANDOM%.bak"
if exist "%RECOVERY_BACKUP%" goto :choose_backup
copy /B "%TARGET_DIR%\minion-0.1.0.jar" "%RECOVERY_BACKUP%" >nul
if errorlevel 1 goto :failed

set "STAGED_BY_US=1"
"%JAVA_EXE%" -cp "%PAYLOAD%" JarPatchApplier "%TARGET_DIR%\minion-0.1.0.jar" "%PATCH%" "%TARGET_DIR%\minion-0.1.0.jar.new"
if errorlevel 1 goto :failed
"%JAVA_EXE%" -cp "%PAYLOAD%" JarEntryVerifier verify "%TARGET_DIR%\minion-0.1.0.jar.new" "%TARGET_MANIFEST%"
if errorlevel 1 goto :failed
move /Y "%TARGET_DIR%\minion-0.1.0.jar.new" "%TARGET_DIR%\minion-0.1.0.jar" >nul
if errorlevel 1 goto :failed

echo.
echo V42 small update completed successfully.
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
echo Package verification only: no installed files were changed.
pause
exit /b 0
:wrong_version
echo ERROR: Installed JAR contents do not match V40. Nothing was changed.
echo Do not force this patch onto V36, a custom JAR or another update.
pause
exit /b 1
:bad_hash
echo ERROR: Package payload SHA-256 mismatch. Nothing was changed.
pause
exit /b 1
:no_java
echo ERROR: Java runtime was not found. Nothing was changed.
pause
exit /b 1
:staged_exists
echo ERROR: minion-0.1.0.jar.new already exists. Nothing was changed.
pause
exit /b 1
:failed
if defined STAGED_BY_US if exist "%TARGET_DIR%\minion-0.1.0.jar.new" del /Q "%TARGET_DIR%\minion-0.1.0.jar.new" >nul 2>nul
if defined RECOVERY_BACKUP if exist "%RECOVERY_BACKUP%" copy /B /Y "%RECOVERY_BACKUP%" "%TARGET_DIR%\minion-0.1.0.jar" >nul 2>nul
echo ERROR: Update failed. Previous JAR restored if a backup was created.
echo Backup: %RECOVERY_BACKUP%
pause
exit /b 1
:missing_payload
echo ERROR: Extract the complete ZIP first; payload is missing.
pause
exit /b 1
:cancelled
echo Update cancelled. Nothing was changed.
pause
exit /b 2
