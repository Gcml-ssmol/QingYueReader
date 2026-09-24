@echo off
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0run_analysis.ps1" -Rebuild
set "run_code=%ERRORLEVEL%"
echo.
if not "%run_code%"=="0" echo Run failed. Read the message above.
pause
exit /b %run_code%
