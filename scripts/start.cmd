@echo off
where py >nul 2>&1
if errorlevel 1 goto use_python
py -3 "%~dp0start.py" %*
exit /b %errorlevel%

:use_python
where python >nul 2>&1
if errorlevel 1 (
    echo Python 3.11+ is required. Install Python and add it to PATH.
    exit /b 1
)
python "%~dp0start.py" %*
exit /b %errorlevel%
