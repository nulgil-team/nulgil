@echo off
rem Starts the local DEMO backend and the frontend. Run BUILD_BACKEND.ps1 once after a fresh clone.
cd /d "%~dp0.."
start "routine backend" cmd /c START_BACKEND.cmd
timeout /t 3 /nobreak >nul
start "routine frontend" node frontend\dev-server.mjs
timeout /t 2 /nobreak >nul
start "" http://localhost:5173/
