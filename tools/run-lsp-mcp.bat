@echo off
setlocal enabledelayedexpansion

set SCRIPT_DIR=%~dp0
set WORKSPACE_ROOT=%SCRIPT_DIR%..
for %%i in ("%WORKSPACE_ROOT%") do set WORKSPACE_ROOT=%%~fi

set DIST_LAUNCHER=%WORKSPACE_ROOT%\retro-lsp\dist\windows\bin\launcher.bat
set VSCODE_LAUNCHER=%WORKSPACE_ROOT%\retro-lsp\vscode\server\bin\launcher.bat

set HAS_WORKSPACE=0
for %%a in (%*) do (
    if "%%a"=="--workspace" set HAS_WORKSPACE=1
)

set WORKSPACE_ARG=
if %HAS_WORKSPACE% equ 0 (
    set WORKSPACE_ARG=--workspace "%WORKSPACE_ROOT%"
)

if exist "%DIST_LAUNCHER%" (
    "%DIST_LAUNCHER%" --mcp %WORKSPACE_ARG% %*
    exit /b %ERRORLEVEL%
)

if exist "%VSCODE_LAUNCHER%" (
    "%VSCODE_LAUNCHER%" --mcp %WORKSPACE_ARG% %*
    exit /b %ERRORLEVEL%
)

where java >nul 2>&1
if %ERRORLEVEL% equ 0 (
    for %%f in ("%WORKSPACE_ROOT%\retro-lsp\target\retro-lsp-*.jar") do (
        echo %%~nxf | findstr /v "sources javadoc tests" >nul && set MAIN_JAR=%%f
    )
    if defined MAIN_JAR (
        java -p "%WORKSPACE_ROOT%\retro-lsp\target\dependency;!MAIN_JAR!" --add-modules ALL-MODULE-PATH -m dev.secondsun.retrolsp/dev.secondsun.retrolsp.Main --mcp %WORKSPACE_ARG% %*
        exit /b %ERRORLEVEL%
    )
)

echo Error: retro-lsp launcher not found. Run tools\build-all.sh first. >&2
exit /b 1

