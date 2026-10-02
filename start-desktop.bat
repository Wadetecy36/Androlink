@echo off
title Androlink Desktop Host
cd /d "%~dp0desktop"
echo ===================================================
echo Starting Androlink Lightweight Desktop Host...
echo ===================================================
start http://localhost:8702
node server/server.js
pause
