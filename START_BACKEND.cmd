@echo off
setlocal
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0START_BACKEND.ps1"
exit /b %errorlevel%

