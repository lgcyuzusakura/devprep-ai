@echo off
where node >nul 2>&1
if errorlevel 1 (
    echo Node.js 20+ and JDK 17+ are required. Install Node.js and add it to PATH.
    exit /b 1
)
node "%~dp0start.mjs" %*
exit /b %errorlevel%
