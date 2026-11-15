@echo off
setlocal EnableExtensions DisableDelayedExpansion
cd /d "%~dp0"
echo Close both old and new Minion before importing.
echo Use this before the first start in this new directory.
set "OLD_DIR="
set /p "OLD_DIR=Old Minion installation directory (Enter to cancel): "
if not defined OLD_DIR exit /b 2
set "OLD_DIR=%OLD_DIR:"=%"
echo This copies settings, chat history and skills. The old installation is not modified.
choice /C YN /N /M "Import? [Y/N]: "
if errorlevel 2 exit /b 2
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0Import-Old-Settings.ps1" -OldDir "%OLD_DIR%"
set "RC=%ERRORLEVEL%"
if not "%RC%"=="0" echo Import failed. Read the message above before starting Minion.
pause
exit /b %RC%
