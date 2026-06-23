import sys
from pathlib import Path

# Permet d'importer le paquet `receiver` sans installation.
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
