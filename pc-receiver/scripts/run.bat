@echo off
REM Lance l'application de reception PC (Windows).
setlocal
cd /d "%~dp0\.."

if not exist ".venv" (
  echo Creation de l'environnement virtuel...
  python -m venv .venv
  call .venv\Scripts\pip install --upgrade pip
  call .venv\Scripts\pip install -r requirements.txt
)

.venv\Scripts\python -m receiver %*
endlocal
