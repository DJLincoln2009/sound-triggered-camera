# Application de réception PC

Poste de contrôle et de stockage local pour la caméra à déclenchement sonore et manuel.
Implémente le module « Application de Réception (PC) » du cahier des charges (EF-12 → EF-24,
ENF-04, ENF-06 → ENF-10). **Architecture 100 % locale, aucune dépendance cloud.**

Stack : Python 3.10+ / Flask (HTTP + interface web) / flask-sock (WebSocket) /
zeroconf (mDNS) / SQLite (métadonnées) / cryptography (TLS auto-signé optionnel).

## Fonctions

- Serveur **WebSocket** du canal de commandes (EF-21/EF-22) et **HTTP** d'upload vidéo (EF-23).
- **Découverte mDNS** : publie `_soundcam._tcp` ; la caméra se connecte automatiquement (EF-12).
- **Appairage** par code PIN, token fort émis ensuite (ENF-06) ; **TLS** optionnel (`--tls`, ENF-07).
- Tableau de bord : état des caméras (EF-13/ENF-10), **déclencher** (EF-14) / **arrêter** (EF-15).
- **Bibliothèque** : stockage horodaté (EF-16), lecture intégrée (EF-17), archivage/suppression (EF-20).
- **Réglages distants** (EF-18) et **journal d'événements** (EF-19).

## Démarrage rapide

```bash
# Linux / macOS
./scripts/run.sh --name "Salon PC"

# Windows
scripts\run.bat --name "Salon PC"

# ou manuellement
python -m venv .venv && . .venv/bin/activate
pip install -r requirements.txt
python -m receiver --name "Salon PC"
```

Au démarrage, la console affiche l'URL de l'interface (ex. `http://192.168.1.20:8766/`)
et le **code d'appairage (PIN)** à saisir dans l'app caméra.

### Options

| Option | Description |
|--------|-------------|
| `--port N` | Port HTTP + WebSocket (défaut 8766). |
| `--name "…"` | Nom lisible de l'appareil de réception. |
| `--tls` | Active `https`/`wss` avec certificat auto-signé (ENF-07). |
| `--data-dir DIR` | Répertoire des vidéos, base et config. |

## Tester sans téléphone

Un **simulateur de caméra** (client de référence du protocole) permet de tout valider :

```bash
# Découverte mDNS automatique
python scripts/camera_simulator.py --pin 123456 --upload sample.mp4
# ou URL directe
python scripts/camera_simulator.py --pin 123456 --url ws://127.0.0.1:8766
```

## Tests

```bash
. .venv/bin/activate && pip install pytest
pytest -q
```

Couvre protocole, appairage, stockage, API REST et un **scénario bout-en-bout**
(serveur réel + caméra simulée : appairage, déclenchement, ACK, upload).

## Empaqueter en exécutable

```bash
./scripts/build_executable.sh   # -> dist/soundcam-receiver
```

## Données stockées

| Élément | Emplacement par défaut |
|---------|------------------------|
| Vidéos | `<data-dir>/recordings/AAAA-MM-JJ/` |
| Base SQLite | `<data-dir>/soundcam.sqlite3` |
| Appairage | `<data-dir>/pairing.json` |
| Réglages caméra | `<data-dir>/camera_settings.json` |

`<data-dir>` : `~/.local/share/soundcam-receiver` (Linux), `~/Library/Application Support/SoundCamReceiver`
(macOS), `%APPDATA%\SoundCamReceiver` (Windows).
