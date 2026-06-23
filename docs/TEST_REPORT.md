# Rapport de tests

## 1. Résumé

| Module | Type de validation | Résultat |
|--------|--------------------|----------|
| Réception PC | Tests automatisés (pytest) — unitaires + intégration bout-en-bout | **25 / 25 réussis** |
| Caméra Android | Compilation complète (Gradle + SDK Android 34) | **APK debug produit** |
| Caméra iOS | Revue de code + conformité protocole | Code complet (build nécessite macOS/Xcode) |
| Protocole | Schéma JSON + tests de (dé)sérialisation | Couvert par les tests PC |

## 2. Application de réception (PC) — tests automatisés

Commande : `cd pc-receiver && pytest -q`

```
25 passed
```

Répartition :

| Fichier | Portée | Exigences couvertes |
|---------|--------|---------------------|
| `tests/test_protocol.py` | Construction/parsing des messages, versionnage | EF-21, EF-22 |
| `tests/test_pairing.py` | PIN, token fort, comparaison à temps constant, révocation | ENF-06 |
| `tests/test_storage.py` | SQLite + fichiers, idempotence par `recording_id`, journal | EF-16, EF-19, EF-20 |
| `tests/test_api.py` | Endpoints REST (state, trigger, stop, settings, recordings, events) | EF-13/14/15/17/18 |
| `tests/test_integration.py` | **Serveur réel + caméra simulée** : appairage → en ligne → trigger+ACK → stop → upload stocké ; rejet d'une caméra non appairée | EF-05/06/09/12/14/15/23, ENF-06 |

Le test d'intégration démarre un vrai serveur (`werkzeug.make_server`) et un **simulateur
de caméra** (`scripts/camera_simulator.py`, client de référence du protocole) qui :
1. s'appaire par PIN et reçoit le token fort (`HELLO`/`HELLO_ACK`) ;
2. apparaît « en ligne » côté PC (`STATUS`) ;
3. reçoit `START_RECORDING`, répond `ACK`, puis `STOP_RECORDING` ;
4. téléverse une vidéo factice (`POST /upload`) que le PC stocke et expose.

## 3. Application caméra Android — compilation

Toolchain installée sur l'environnement de build : **JDK 17**, **Gradle 8.9**,
**Android SDK** (platform-tools, `platforms;android-34`, `build-tools;34.0.0`).

Commande : `cd android && ./gradlew assembleDebug`

```
BUILD SUCCESSFUL
-> app/build/outputs/apk/debug/app-debug.apk
```

Tous les modules Kotlin compilent (service, capture CameraX, détection audio, client
WebSocket OkHttp, upload, découverte NSD, UI Compose, classifieur TFLite). Avertissements
restants : usages d'API dépréciées (`NsdManager.resolveService`, `menuAnchor`) — sans
impact fonctionnel sur la plage `minSdk 29 → compileSdk 34`.

## 4. Application caméra iOS — état

Le code Swift/SwiftUI est complet et conforme au protocole (mêmes constantes, mêmes
champs). La **compilation requiert macOS + Xcode** (frameworks AVFoundation/Network/UIKit) ;
le projet se génère avec `xcodegen generate` (voir `ios/README.md`). Conformément au §7.2,
la capture vidéo est limitée au premier plan (vérifiable uniquement sur appareil iOS réel).

## 5. Exigences nécessitant du matériel réel

Les exigences de **performance/endurance** suivantes sont implémentées mais leur critère
d'acceptation impose une mesure sur appareils et réseaux réels :

| ID | À mesurer |
|----|-----------|
| ENF-01 | Délai déclenchement → capture < 2 s (moy.), < 4 s (max) sur 20 essais |
| ENF-02 | Transfert 1 min 720p < 30 s sur 3 réseaux domestiques |
| ENF-03 | Service Android stable 24 h |
| ENF-14 | Surconsommation batterie < 20 % sur 24 h |

L'architecture y répond (connexion persistante, capture immédiate, transfert différé,
détection légère, heartbeat 2 s), mais les chiffres dépendent du matériel cible.

## 6. Reproduire

```bash
# PC
cd pc-receiver && ./scripts/run.sh         # lance le serveur (PIN affiché)
cd pc-receiver && pytest -q                 # 25 tests

# Simulateur de caméra (sans téléphone), dans un autre terminal :
python pc-receiver/scripts/camera_simulator.py --pin <PIN_AFFICHÉ>

# Android
cd android && ./gradlew assembleDebug
```
