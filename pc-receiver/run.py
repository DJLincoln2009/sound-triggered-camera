"""Lanceur direct (utile pour PyInstaller et `python run.py`)."""
from receiver.__main__ import main

if __name__ == "__main__":
    raise SystemExit(main())
