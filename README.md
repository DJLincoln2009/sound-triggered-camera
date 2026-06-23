# Caméra connectée à déclenchement sonore et manuel

Système de surveillance **100 % local** (aucun cloud) qui transforme un smartphone en
caméra déclenchée **par le son** (automatique) ou **à distance** (manuelle), avec
réception, stockage et pilotage depuis un **PC**. Technologies **gratuites et open source**.
Option de **diffusion en direct (WebRTC)**, activée au choix depuis le PC.

> Réalisation complète du cahier des charges et de l'étude de faisabilité : trois
> applications (Android, iOS allégée, PC) + un protocole réseau partagé.

## Arborescence

```
sound-triggered-camera/
├── protocol/          Contrat réseau partagé (source de vérité)
│   ├── PROTOCOL.md            Spécification des canaux et messages
│   └── messages.schema.json   JSON Schema des messages
├── pc-receiver/       Application de réception PC (Python / Flask)
│   ├── receiver/              Serveur WS + HTTP, GUI web, SQLite, mDNS, appairage, TLS
│   ├── scripts/              run.sh/run.bat, build_executable.sh, camera_simulator.py
│   ├── tests/               25 tests (pytest)
│   └── requirements.txt
├── android/           Application caméra Android (Kotlin / CameraX / Compose)
│   └── app/src/main/java/com/soundcam/camera/  service, capture, audio, net, ui…
├── ios/               Application caméra iOS — périmètre allégé (Swift / SwiftUI)
│   ├── project.yml           Spécification XcodeGen
│   └── SoundCam/            Sources Swift
├── docs/              Architecture, installation, guide, traçabilité, rapport de tests
└── scripts/           Scripts de build/déploiement multi-modules
```

## Modules

| Module | Rôle | Stack | État |
|--------|------|-------|------|
| **Protocole** | Contrat réseau (WS commandes + HTTP vidéo) | Markdown + JSON Schema | ✅ |
| **Réception PC** | Serveur, GUI de pilotage, stockage | Python / Flask / SQLite | ✅ 25 tests |
| **Caméra Android** | Capture, détection sonore, transfert | Kotlin / CameraX / Compose | ✅ APK compilé |
| **Caméra iOS** | Idem (périmètre réduit §7.2) | Swift / AVFoundation | ✅ code complet |

## Démarrage rapide

```bash
# 1) PC (serveur + tableau de bord)
cd pc-receiver && ./scripts/run.sh          # affiche l'URL du dashboard et le PIN

# 2) Caméra Android
cd android && ./gradlew installDebug         # nécessite JDK 17 + SDK Android 34

# 3) (option) Tester le protocole sans téléphone
python pc-receiver/scripts/camera_simulator.py --pin <PIN_AFFICHÉ>
```

Sur le téléphone : autoriser caméra/micro, saisir le **PIN** (onglet Réglages), **Démarrer**.
La caméra est découverte automatiquement (mDNS) et apparaît dans le tableau de bord PC.

## Documentation

- [Architecture](docs/ARCHITECTURE.md) — choix techniques et fonctionnement interne
- [Installation & exécution](docs/INSTALLATION.md)
- [Guide utilisateur](docs/USER_GUIDE.md)
- [Protocole réseau](protocol/PROTOCOL.md)
- [Matrice de traçabilité des exigences](docs/REQUIREMENTS_TRACEABILITY.md)
- [Rapport de tests](docs/TEST_REPORT.md)

## Principes d'architecture

- **Local d'abord** : tout transite sur le Wi-Fi local ; aucune dépendance cloud, aucune
  télémétrie (EF-24 / ENF-08).
- **PC serveur, caméras clientes** : découverte mDNS, reconnexion automatique, l'opérateur
  ne saisit aucune IP.
- **Trois canaux** : commandes (WebSocket, bidirectionnel), vidéo différée (HTTP, avec
  reprise) et **diffusion en direct optionnelle** (WebRTC P2P sur le LAN, signaling relayé
  par le PC, activée au choix — voir [PROTOCOL.md §6](protocol/PROTOCOL.md)).
- **Live 100 % local** : aucun serveur STUN/TURN externe ; le navigateur du dashboard est
  le récepteur WebRTC, le PC ne fait que relayer le signaling.
- **Sécurité** : appairage obligatoire (PIN → token fort), TLS optionnel (certificat
  auto-signé), modèle TOFU sur réseau de confiance.
- **Séparation claire** : `protocol/` est la source de vérité ; chaque app implémente le
  même contrat dans son langage.

## Licence

Composants tiers sous licences open source respectives. Voir l'en-tête de chaque module.
