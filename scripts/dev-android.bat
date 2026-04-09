@echo off
setlocal
REM Thin wrapper: run the PowerShell developer workflow (see docs/android-dev-setup.md).
set SCRIPT_DIR=%~dp0
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%SCRIPT_DIR%dev-android.ps1" %*
