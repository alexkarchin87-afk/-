@echo off
setlocal
cd /d "%~dp0"
if not exist .venv\Scripts\python.exe py -3.12 -m venv .venv
if errorlevel 1 exit /b 1
.venv\Scripts\python.exe -m pip install -r requirements.txt pyinstaller==6.16.0
if errorlevel 1 exit /b 1
.venv\Scripts\python.exe -m PyInstaller --noconfirm --clean --onedir --windowed --name VanyaBridge --collect-all playwright --collect-submodules uvicorn desktop_entry.py
if errorlevel 1 exit /b 1
echo Output: dist\VanyaBridge\VanyaBridge.exe
