@echo off
setlocal
cd /d "%~dp0"
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0build-apk.ps1" %*
if errorlevel 1 (
  echo.
  echo Build failed. Read the error above and see BUILD-APK.md.
  pause
  exit /b 1
)
echo.
echo APK is ready in the dist folder.
pause
