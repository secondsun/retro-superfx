@echo off
setlocal
set SCRIPT_DIR=%~dp0
set VENV_DIR=%SCRIPT_DIR%.venv

if not exist "%VENV_DIR%\Scripts\python.exe" (
    echo Creating virtual environment at %VENV_DIR%... >&2
    where python >nul 2>&1
    if %ERRORLEVEL% equ 0 (
        python -m venv "%VENV_DIR%"
    ) else (
        python3 -m venv "%VENV_DIR%"
    )
    "%VENV_DIR%\Scripts\pip.exe" install -q -r "%SCRIPT_DIR%requirements.txt"
    "%VENV_DIR%\Scripts\pip.exe" install -q -e "%SCRIPT_DIR%"
)

set PYTHONPATH=%SCRIPT_DIR%;%PYTHONPATH%
"%VENV_DIR%\Scripts\python.exe" -m mesen_mcp.server %*

