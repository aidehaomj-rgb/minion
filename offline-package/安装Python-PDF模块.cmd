@echo off
setlocal
chcp 65001 >nul
set "PKG_DIR=%~dp0"
set "PYTHON_EXE=%~1"
if not defined PYTHON_EXE set "PYTHON_EXE=C:\ProgramData\Anaconda3\python.exe"
if not exist "%PYTHON_EXE%" (
  echo 未找到 Python: %PYTHON_EXE%
  echo 用法: 安装Python-PDF模块.cmd "D:\Anaconda3\python.exe"
  pause
  exit /b 2
)
if exist "%PKG_DIR%python-wheels" (
  "%PYTHON_EXE%" -m pip install --no-index --find-links "%PKG_DIR%python-wheels" -r "%PKG_DIR%python-requirements-win7-py37.txt"
) else (
  echo 缺少离线目录: %PKG_DIR%python-wheels
  pause
  exit /b 3
)
"%PYTHON_EXE%" -c "import sqlite3,PyPDF2,pdfminer;print('sqlite3',sqlite3.sqlite_version);print('PyPDF2',PyPDF2.__version__);print('pdfminer',pdfminer.__version__)"
pause
