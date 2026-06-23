# Architecture

## 1. Vue d'ensemble

Le système met en relation des **applications caméra** (mobiles) et une **application de
réception** (PC) **exclusivement sur le réseau Wi-Fi local**. Aucun composant cloud
n'intervient (EF-24 / ENF-08).

```
                          Réseau Wi-Fi local (aucun cloud)
   ┌─────────────────────────────────────────────────────────────────────┐
   │                                                                       │
   │   ┌───────────────┐        WebSocket /ws  (commandes)   ┌──────────┐  │
   │   │ App Caméra     │  ─────────────────────────────────▶│          │  │
   │   │ Android / iOS  │◀─────────────────────────────────  │  App PC  │  │
   │   │ (client)       │        HTTP POST /upload (vidéo)    │ (serveur)│  │
   │   │                │  ─────────────────────────────────▶│          │  │
   │   └───────────────┘        mDNS  _soundcam._tcp  ◀────── │  + GUI   │  │
   │                                       (annonce)          └────┬─────┘  │
   │                                                               │        │
   │                                                        SQLite + fichiers│
   └─────────────────────────────────────────────────────────────────────┘
```

Trois modules + un protocole partagé, **clairement séparés** :

| Module | Répertoire | Rôle | Stack |
|--------|-----------|------|-------|
| Protocole | `protocol/` | Contrat réseau (source de vérité) | Markdown + JSON Schema |
| Réception PC | `pc-receiver/` | Serveur WS + HTTP, GUI, stockage | Python / Flask |
| Caméra Android | `android/` | Capture, détection, client | Kotlin / CameraX |
| Caméra iOS | `ios/` | Idem, périmètre allégé | Swift / AVFoundation |

## 2. Choix : le PC est serveur, la caméra est client

Conforme à la spécification (§7.1 : « Client WebSocket persistant vers le serveur hébergé
sur l'appareil de réception »). Avantages :

- La caméra **découvre** le PC par mDNS et s'y connecte : l'opérateur ne saisit aucune IP.
- Dès qu'une caméra se connecte, elle **apparaît** dans la liste du PC (EF-12/EF-13).
- La reconnexion est portée par le client (backoff exponentiel), naturel pour un mobile
  dont la connectivité varie (ENF-04).

## 3. Deux canaux logiques

1. **Canal de commandes** — WebSocket bidirectionnel (`/ws`). Messages JSON typés
   (`HELLO`, `START_RECORDING`, `STOP_RECORDING`, `SET_SETTINGS`, `STATUS`, `ACK`,
   `SOUND_TRIGGERED`). Voir `protocol/PROTOCOL.md`.
2. **Canal vidéo** — HTTP `POST /upload` (multipart), **caméra → PC**, **différé** : la
   caméra enregistre localement puis téléverse, avec reprise après coupure (EF-08/EF-09).

Séparer commandes (petits messages temps réel) et vidéo (gros transferts) évite que
l'upload ne bloque le pilotage, et simplifie la résilience.

## 4. Déclenchement : sonore + manuel, unifiés

- **Sonore (local caméra)** : écoute continue du micro (`SoundDetector`). Seuil
  d'amplitude par défaut ; option classifieur **YAMNet** (TFLite) pour cibler des
  catégories de sons (EF-03/EF-04).
- **Manuel (distant PC)** : l'opérateur clique « Déclencher » → `START_RECORDING` →
  la caméra enregistre et renvoie un `ACK` (EF-05/EF-06/EF-14).
- **Unification & concurrence (EF-07)** : les deux origines mènent à la même action. Si un
  enregistrement est déjà en cours, une nouvelle demande le **prolonge** sans créer un
  second flux ; les déclenchements sont idempotents par enregistrement actif.

## 5. Application de réception (PC)

Modules (`pc-receiver/receiver/`) :

| Fichier | Responsabilité |
|---------|----------------|
| `app.py` | Application Flask : GUI, `/ws`, `/upload`, API REST |
| `hub.py` | `CameraHub`/`CameraConnection` : état des caméras, envoi de commandes + attente d'ACK |
| `storage.py` | SQLite (métadonnées + journal) et fichiers, thread-safe |
| `pairing.py` | PIN + token fort, vérification à temps constant (ENF-06) |
| `discovery.py` | Publication mDNS `_soundcam._tcp` (EF-12) |
| `tls.py` | Génération d'un certificat auto-signé (ENF-07) |
| `config.py` | Arguments, chemins par OS, réglages caméra |
| `protocol.py` | Constructeurs/parseurs de messages (miroir du protocole) |
| `web/` | Interface de pilotage (HTML/CSS/JS, polling REST) |

Concurrence : Flask en mode threadé ; `Storage` et `CameraHub` protégés par des verrous
(`RLock`). Chaque connexion WebSocket et chaque requête HTTP s'exécutent dans leur thread.

## 6. Application caméra Android

Foreground service (`CameraService`) hébergeant tous les sous-systèmes (EF-02) :

```
CameraService (LifecycleService, notification persistante)
 ├── VideoRecorder      CameraX VideoCapture (EF-01)
 ├── SoundDetector      AudioRecord + RMS, option YAMNet (EF-03/EF-04)
 ├── CommandClient      WebSocket OkHttp + reconnexion (EF-05/EF-06)
 ├── VideoUploader      Upload multipart + reprise (EF-08/EF-09)
 ├── Discovery          NSD _soundcam._tcp (EF-12)
 ├── PendingUploads     File locale (sidecars .meta.json)
 └── SettingsRepository DataStore (réglages + appairage)
```

Point délicat : **le micro**. En écoute, `SoundDetector` lit le PCM ; avant une capture
CameraX (qui enregistre son propre audio), le détecteur **libère** le micro, puis le
reprend après finalisation.

## 7. Application caméra iOS (périmètre allégé, §7.2)

Mêmes briques (`CameraRecorder` AVFoundation, `SoundDetector` AVAudioEngine,
`CommandClient` URLSession, `Discovery` Network/Bonjour), orchestrées par
`CameraController`. Différence imposée par iOS : **la capture vidéo n'a lieu qu'au premier
plan**. L'écoute sonore continue en arrière-plan (mode `audio`) ; un son détecté en
arrière-plan est **signalé au PC** mais ne démarre pas la caméra. L'utilisateur est informé
de ces limites dans l'app (onglet Infos).

## 8. Sécurité & vie privée

- **Appairage obligatoire** (PIN → token fort) avant toute commande (ENF-06).
- **TLS optionnel** (certificat auto-signé local, TOFU) pour chiffrer commandes + vidéo
  (ENF-07).
- **Zéro cloud, zéro télémétrie** : aucun trafic sortant hors réseau local (ENF-08).
- **Transparence** : notification persistante pendant le service (Android) et indicateur
  REC + écran d'information (iOS), conformément au §9.2.

## 9. Évolutivité

- Protocole **versionné** (`protocol` entier) ; un `HELLO_ACK` peut rejeter une version
  incompatible.
- Le `messages.schema.json` permet de valider/évoluer les trames.
- Les ports WS/HTTP sont distincts dans le TXT mDNS (aujourd'hui identiques) pour
  permettre une séparation future sans changer les clients.
