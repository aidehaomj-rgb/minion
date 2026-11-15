@echo off
setlocal EnableExtensions DisableDelayedExpansion
cd /d "%~dp0"
if not exist "%~dp0Minion.exe" (
  echo Place this file beside Minion.exe in the existing installation.
  pause
  exit /b 1
)
"%~dp0Minion.exe" --install-shortcuts
if errorlevel 1 (
  echo Shortcuts were not fully created. Read the message above; existing shortcuts are not overwritten.
  pause
  exit /b 1
)
exit /b 0
