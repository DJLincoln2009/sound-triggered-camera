# Matrice de traçabilité des exigences

Priorités : **M** = Must, **S** = Should, **C** = Could.
Statut : ✅ implémenté · ⚠️ partiel/documenté · ➖ hors périmètre code.

## Exigences fonctionnelles — Application caméra (mobile)

| ID | Prio | Exigence (résumé) | Statut | Implémentation |
|----|:----:|-------------------|:------:|----------------|
| EF-01 | M | Capturer une vidéo | ✅ | Android `capture/VideoRecorder.kt` (CameraX) ; iOS `Capture/CameraRecorder.swift` (AVFoundation) |
| EF-02 | M | Fonctionner en arrière-plan | ✅ (Android) / ⚠️ (iOS) | Android `service/CameraService.kt` (foreground service) ; iOS : capture 1er plan, écoute audio en arrière-plan (§7.2) |
| EF-03 | M | Écoute continue du micro + seuil | ✅ | `audio/SoundDetector.kt` ; `Audio/SoundDetector.swift` (RMS) |
| EF-04 | S | Modèle de classification (faux positifs) | ✅ | `audio/ClassifierEngine.kt` (YAMNet TFLite, repli amplitude) |
| EF-05 | M | Recevoir/exécuter le déclenchement manuel | ✅ | `net/CommandClient.kt` → `CameraService.startRecording` ; iOS `CommandClient`+`CameraController` |
| EF-06 | M | Envoyer un ACK pour chaque commande | ✅ | `CameraService` / `CameraController` `sendAck`; PC `hub.send_command_await_ack` |
| EF-07 | M | Gérer la concurrence son/manuel | ✅ | Contrôleur d'enregistrement unique (prolongation, idempotent) dans le service |
| EF-08 | M | Stocker localement les enregistrements | ✅ | `data/PendingUploads.kt` ; `Data/PendingUploads.swift` |
| EF-09 | M | Transférer les enregistrements vers le PC | ✅ | `net/VideoUploader.kt` ; `Net/VideoUploader.swift` (POST `/upload`) |
| EF-10 | S | Configuration locale (seuil, qualité…) | ✅ | `data/SettingsRepository.kt` ; `Data/AppSettings.swift` ; écrans Réglages |
| EF-11 | C | Signaler marque/modèle + guidage batterie | ✅ | `util/BatteryGuidance.kt` (Android) ; modèle envoyé dans `HELLO` (iOS/Android) |

## Exigences fonctionnelles — Application de réception (PC)

| ID | Prio | Exigence (résumé) | Statut | Implémentation |
|----|:----:|-------------------|:------:|----------------|
| EF-12 | M | Découvrir automatiquement les caméras | ✅ | `discovery.py` (mDNS) + handshake `app.py` ; caméra via NSD/Bonjour |
| EF-13 | M | Afficher l'état de connexion | ✅ | `hub.py` snapshots + `web/` (polling `/api/state`) |
| EF-14 | M | Contrôle « Déclencher » | ✅ | `POST /api/trigger` → `START_RECORDING` ; bouton dans `web/` |
| EF-15 | M | Contrôle « Arrêter » | ✅ | `POST /api/stop` → `STOP_RECORDING` |
| EF-16 | M | Recevoir et stocker les enregistrements | ✅ | `app.py` `/upload` → `storage.py` |
| EF-17 | M | Lecture des enregistrements | ✅ | `/api/recordings/<id>/file` + lecteur dans `web/app.js` |
| EF-18 | S | Modification à distance des réglages | ✅ | `POST /api/settings` → `SET_SETTINGS` poussé aux caméras |
| EF-19 | S | Journal des événements | ✅ | `storage.py` table `events` + `/api/events` + onglet Événements |
| EF-20 | C | Supprimer / archiver | ✅ | `DELETE /api/recordings/<id>`, `POST .../archive` |

## Exigences fonctionnelles — Protocole & communication

| ID | Prio | Exigence (résumé) | Statut | Implémentation |
|----|:----:|-------------------|:------:|----------------|
| EF-21 | M | Canal de commandes | ✅ | WebSocket `/ws` (`app.py`, `CommandClient`) |
| EF-22 | M | Jeu de messages typés | ✅ | `protocol/PROTOCOL.md`, `messages.schema.json`, `protocol.py`/`Messages.kt`/`Messages.swift` |
| EF-23 | M | Canal vidéo dédié | ✅ | HTTP `POST /upload` multipart |
| EF-24 | M | Fonctionnement 100 % local | ✅ | Aucun appel sortant ; mDNS + LAN uniquement |

## Exigences non fonctionnelles

| ID | Prio | Exigence (résumé) | Statut | Prise en charge |
|----|:----:|-------------------|:------:|-----------------|
| ENF-01 | M | Latence déclenchement < 2 s | ✅ | Connexion WS persistante + ACK ; capture démarrée immédiatement (à valider sur matériel) |
| ENF-02 | S | Transfert 1 min 720p < 30 s | ⚠️ | Transfert différé HTTP local ; dépend du réseau (à mesurer) |
| ENF-03 | M | Service Android stable 24 h | ✅ | Foreground service + guidage batterie (EF-11) ; endurance à valider sur matériel |
| ENF-04 | M | Commande non exécutée signalée | ✅ | `hub.send_command_await_ack` (timeout) → erreur visible côté PC |
| ENF-05 | M | Enregistrement préservé en cas de coupure | ✅ | Enregistrement local + reprise d'upload (`PendingUploads`/`VideoUploader`) |
| ENF-06 | M | Authentification (jumelage) | ✅ | `pairing.py` (PIN → token fort), `HELLO`/`HELLO_ACK` |
| ENF-07 | S | Chiffrement des échanges | ✅ | Option `--tls` (certificat auto-signé `tls.py`) ; clients TOFU |
| ENF-08 | M | Aucune transmission cloud | ✅ | Aucune dépendance/appel externe ; tout sur LAN |
| ENF-09 | M | Bouton déclenchement visible en 1 clic | ✅ | Bouton **Déclencher** sur le tableau de bord |
| ENF-10 | M | État visible en permanence (3 états) | ✅ | Badges couleur connecté/hors-ligne/enregistrement (`web/`) |
| ENF-11 | M | Android 10+ | ✅ | `minSdk 29` (`android/app/build.gradle.kts`) |
| ENF-12 | S | PC sous Windows et macOS | ✅ | Python multiplateforme ; chemins par OS (`config.py`) |
| ENF-13 | C | iOS compatible mode audio arrière-plan | ✅ | `UIBackgroundModes: audio` (`ios/project.yml`) |
| ENF-14 | S | Surconsommation batterie < 20 % | ⚠️ | Détection amplitude légère, heartbeat 2 s, transfert différé ; à mesurer sur matériel |

## Notes

- Les statuts ⚠️ concernent des exigences **de performance/endurance** dont le critère
  d'acceptation requiert une **mesure sur matériel réel** (réseaux et appareils de
  référence). L'implémentation est en place ; voir `docs/TEST_REPORT.md`.
- iOS est volontairement **allégé** (§7.2) : EF-02 partiel (capture au premier plan).
