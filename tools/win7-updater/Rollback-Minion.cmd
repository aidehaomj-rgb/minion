@echo off
setlocal EnableExtensions DisableDelayedExpansion
cd /d "%~dp0"
set "SAVED_DIR=%~dp0install-dir.txt"
set "TARGET_DIR="
title Minion Rollback
echo ========================================
echo Minion offline rollback
echo ========================================
if exist "%~dp0minion-0.1.0.jar" set "TARGET_DIR=%~dp0"
if not defined TARGET_DIR if exist "%~dp0..\minion-0.1.0.jar" set "TARGET_DIR=%~dp0.."
if not defined TARGET_DIR if exist "%SAVED_DIR%" set /p TARGET_DIR=<"%SAVED_DIR%"
:check_target
if defined TARGET_DIR if exist "%TARGET_DIR%\backup\minion-0.1.0.jar.bak" goto :target_ok
echo Backup was not found automatically.
set "TARGET_DIR="
set /p "TARGET_DIR=Enter Minion installation directory: "
if not defined TARGET_DIR goto :cancelled
set "TARGET_DIR=%TARGET_DIR:"=%"
goto :check_target
:target_ok
for %%I in ("%TARGET_DIR%") do set "TARGET_DIR=%%~fI"
echo Installation: %TARGET_DIR%
echo Close Minion before continuing.
choice /C YN /N /M "Restore previous JAR? [Y/N]: "
if errorlevel 2 goto :cancelled
copy /Y "%TARGET_DIR%\backup\minion-0.1.0.jar.bak" "%TARGET_DIR%\minion-0.1.0.jar.rollback" >nul
if errorlevel 1 goto :failed
move /Y "%TARGET_DIR%\minion-0.1.0.jar.rollback" "%TARGET_DIR%\minion-0.1.0.jar" >nul
if errorlevel 1 goto :failed
echo Rollback completed successfully.
pause
exit /b 0
:failed
if exist "%TARGET_DIR%\minion-0.1.0.jar.rollback" del /Q "%TARGET_DIR%\minion-0.1.0.jar.rollback" >nul 2>nul
echo ERROR: Rollback failed. Check file permissions and make sure Minion is closed.
pause
exit /b 1
:cancelled
echo Rollback cancelled.
pause
exit /b 2
