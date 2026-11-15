@echo off
setlocal EnableExtensions DisableDelayedExpansion
cd /d "%~dp0"
title Minion V47 small update
set "PAYLOAD=%~dp0payload"
echo ========================================
echo Minion V47 small update - V40/V41/V42/V43/V44/V45/V46
echo ========================================
echo Extract the complete ZIP before running this program.
echo Close Minion first. User settings and data will be preserved.
echo.
for %%F in (JarPatchApplier.class JarEntryVerifier.class MinionSmallUpdate.class entries-patch.zip v40-entries.tsv v41-entries.tsv v42-entries.tsv v43-entries.tsv v44-entries.tsv v45-entries.tsv v46-entries.tsv v47-entries.tsv Minion.exe Create-Minion-Shortcuts.cmd) do (
    if not exist "%PAYLOAD%\%%F" goto :incomplete
    if not exist "%PAYLOAD%\%%F.sha256" goto :incomplete
    call :check_hash "%PAYLOAD%\%%F" "%PAYLOAD%\%%F.sha256"
    if errorlevel 1 goto :corrupt
)
if /I "%~1"=="/verify" goto :verified
set "TARGET_DIR=%~1"
if not defined TARGET_DIR if exist "%~dp0minion-0.1.0.jar" set "TARGET_DIR=%~dp0"
if not defined TARGET_DIR if exist "%~dp0..\minion-0.1.0.jar" set "TARGET_DIR=%~dp0.."
:select_target
if defined TARGET_DIR if exist "%TARGET_DIR%\minion-0.1.0.jar" goto :target_found
echo Enter the installation directory containing minion-0.1.0.jar.
echo Press Enter with an empty value to cancel.
set "TARGET_DIR="
set /p "TARGET_DIR=Installation directory: "
if not defined TARGET_DIR goto :cancelled
set "TARGET_DIR=%TARGET_DIR:"=%"
goto :select_target
:target_found
for %%I in ("%TARGET_DIR%") do set "TARGET_DIR=%%~fI"
set "JAVA_EXE="
if exist "%TARGET_DIR%\jdk8\bin\java.exe" set "JAVA_EXE=%TARGET_DIR%\jdk8\bin\java.exe"
if not defined JAVA_EXE if exist "%TARGET_DIR%\jre\bin\java.exe" set "JAVA_EXE=%TARGET_DIR%\jre\bin\java.exe"
if not defined JAVA_EXE if defined MINION_JAVA if exist "%MINION_JAVA%" set "JAVA_EXE=%MINION_JAVA%"
if not defined JAVA_EXE if defined JAVA_HOME if exist "%JAVA_HOME%\bin\java.exe" set "JAVA_EXE=%JAVA_HOME%\bin\java.exe"
if not defined JAVA_EXE set "JAVA_EXE=java.exe"
"%JAVA_EXE%" -version >nul 2>nul
if errorlevel 1 goto :no_java
"%JAVA_EXE%" -Dfile.encoding=UTF-8 -cp "%PAYLOAD%" MinionSmallUpdate check "%PAYLOAD%" "%TARGET_DIR%"
if errorlevel 1 goto :failed
echo.
echo Close every Minion window before updating. A backup JAR will be created.
choice /C YN /N /M "Start update? [Y/N]: "
if errorlevel 2 goto :cancelled
"%JAVA_EXE%" -Dfile.encoding=UTF-8 -cp "%PAYLOAD%" MinionSmallUpdate apply "%PAYLOAD%" "%TARGET_DIR%"
if errorlevel 1 goto :failed
echo.
echo V47 update finished. Start Minion normally.
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
echo Package SHA-256 checks passed. No installation files were changed.
pause
exit /b 0
:incomplete
echo ERROR: Package files are missing. Extract the complete ZIP first.
pause
exit /b 1
:corrupt
echo ERROR: Package checksum mismatch. No installation files were changed.
pause
exit /b 1
:no_java
echo ERROR: Java 8 runtime was not found. Set JAVA_HOME or MINION_JAVA.
pause
exit /b 1
:failed
echo ERROR: Update did not complete. Read the error above; do not bypass version checks.
echo If a backup was made it is inside the installation backup directory.
pause
exit /b 1
:cancelled
echo Cancelled. No application files were changed.
pause
exit /b 2
