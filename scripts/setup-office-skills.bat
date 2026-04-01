@echo off
setlocal enabledelayedexpansion

echo === Office Skills Setup ===
echo.

set "SCRIPT_DIR=%~dp0"
set "OFFICE_DIR=%SCRIPT_DIR%..\plugins\office-skills"

cd /d "%OFFICE_DIR%"

:: Check Python
where python >nul 2>&1
if %ERRORLEVEL% neq 0 (
    echo [MISSING] python
    echo Please install Python 3.10+ from https://python.org
    echo Make sure to check "Add Python to PATH" during installation.
    pause
    exit /b 1
)
echo [OK] python

:: Check Node
where node >nul 2>&1
if %ERRORLEVEL% neq 0 (
    echo [MISSING] node
    pause
    exit /b 1
)
echo [OK] node

:: Check LibreOffice
where soffice >nul 2>&1
if %ERRORLEVEL% neq 0 (
    echo [MISSING] soffice ^(LibreOffice^)
    echo Install from https://www.libreoffice.org/download/
    echo Or: choco install libreoffice
)

:: Check Poppler
where pdftoppm >nul 2>&1
if %ERRORLEVEL% neq 0 (
    echo [MISSING] pdftoppm ^(Poppler^)
    echo Install from https://github.com/oschwartz10612/poppler-windows/releases
    echo Or: choco install poppler
)

:: Check Pandoc
where pandoc >nul 2>&1
if %ERRORLEVEL% neq 0 (
    echo [MISSING] pandoc
    echo Install from https://pandoc.org/installing.html
    echo Or: choco install pandoc
)

echo.
echo Setting up Python virtual environment...

if not exist "venv" (
    python -m venv venv
    echo Created venv\
)

echo Installing Python packages...
call venv\Scripts\activate.bat
pip install -q -r requirements.txt
echo [OK] Python packages installed

echo.
echo Setting up Node.js dependencies...
if not exist "node_modules" (
    echo Installing npm packages ^(including Playwright Chromium^)...
    call npm install
) else (
    echo [OK] node_modules already exists
)

echo.
echo === Setup Complete ===
echo Office skills ready: PPTX, DOCX, XLSX, PDF
pause
