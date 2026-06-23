# Protocole de communication — Caméra à déclenchement sonore et manuel

Ce document est la **source de vérité** du protocole réseau partagé entre l'application
caméra (mobile) et l'application de réception (PC). Il implémente les exigences
**EF-21 → EF-24** et **ENF-06 → ENF-08** du cahier des charges.

Il est volontairement indépendant du langage : l'app Android (Kotlin), l'app iOS (Swift)
et l'app PC (Python) doivent toutes s'y conformer.

---

## 1. Vue d'ensemble

Deux canaux logiques transitent **exclusivement sur le réseau Wi-Fi local** (aucun cloud,
EF-24 / ENF-08) :

| Canal | Transport | Sens | Rôle |
|-------|-----------|------|------|
| **Canal de commandes** | WebSocket (`ws://` ou `wss://`) | bidirectionnel | Pilotage, réglages, accusés de réception, statut |
| **Canal vidéo** | HTTP (`POST` multipart) | caméra → PC | Transfert différé des enregistrements |

- Le **PC est le serveur** (WebSocket + HTTP). Il publie un service mDNS.
- La **caméra est le client**. Elle découvre le PC par mDNS puis ouvre une connexion
  WebSocket persistante avec reconnexion automatique (backoff exponentiel).

Ce choix correspond à la spécification Android §7.1 (« Client WebSocket persistant vers
le serveur hébergé sur l'appareil de réception ») et permet de satisfaire EF-12 : dès que
la caméra démarre, elle trouve le PC, s'y connecte et **apparaît dans la liste** du PC.

```
   Caméra (client)                                  PC (serveur)
   ───────────────                                  ────────────
        │   1. Résolution mDNS  _soundcam._tcp           │
        │ <───────────────────────────────────────────  │  (annonce)
        │                                                 │
        │   2. WebSocket connect  ws(s)://PC:8766/ws      │
        │ ──────────────────────────────────────────────>│
        │   3. HELLO (+ token d'appairage)                │
        │ ──────────────────────────────────────────────>│
        │   4. HELLO_ACK (accepté / rejeté)               │
        │ <────────────────────────────────────────────  │
        │                                                 │
        │   STATUS (périodique) ─────────────────────────>│
        │ <──────────── START_RECORDING / STOP / SETTINGS │
        │   ACK ─────────────────────────────────────────>│
        │   SOUND_TRIGGERED ─────────────────────────────>│
        │                                                 │
        │   5. Upload HTTP POST /upload (vidéo + méta)     │
        │ ──────────────────────────────────────────────>│
```

---

## 2. Découverte réseau (mDNS / NSD) — EF-12

- Type de service : **`_soundcam._tcp.`**
- Le PC publie : nom d'instance (ex. `SoundCam-Receiver`), son port, et un
  enregistrement TXT. **Un seul port** (par défaut `8766`) héberge à la fois le
  WebSocket (`/ws`) et l'upload HTTP (`/upload`) ; `ws` et `http` portent donc la même
  valeur (champs distincts conservés pour une éventuelle séparation future) :

| Clé TXT | Exemple | Description |
|---------|---------|-------------|
| `ws` | `8766` | port du canal de commandes WebSocket |
| `http` | `8766` | port du canal d'upload vidéo HTTP |
| `tls` | `0` \| `1` | `1` si TLS (wss/https) activé |
| `proto` | `1` | version du protocole |
| `name` | `Salon PC` | nom lisible de l'appareil de réception |

La caméra (Android NSD / iOS Bonjour) **browse** ce type, résout l'hôte et se connecte.

---

## 3. Canal de commandes (WebSocket)

- Encodage : **un message JSON UTF-8 par trame texte WebSocket**.
- Chaque message possède au minimum le champ `type` (chaîne, voir §4).
- Les commandes nécessitant une confirmation portent un `request_id`
  (chaîne, idéalement un UUID court) auquel répond un `ACK` du même `request_id`.
- Horodatages : ISO‑8601 UTC (`2026-06-21T14:32:10Z`).

### 3.1 Appairage et authentification (ENF-06)

Avant d'accepter toute commande, le PC authentifie la caméra par un identifiant partagé.

1. Au premier lancement, le PC génère un **code d'appairage** (PIN à 6 chiffres) affiché
   dans son interface, **et** un **token fort** aléatoire (32 octets), tous deux persistés.
2. L'opérateur saisit ce PIN dans l'app caméra (écran Réglages → Appairage).
3. À la première connexion, la caméra envoie un `HELLO` contenant le **PIN** (champ
   `token`). Le PC l'accepte et renvoie le **token fort** dans `HELLO_ACK` (champ `token`).
4. La caméra **mémorise ce token fort** et l'utilise pour ses reconnexions WebSocket et
   pour l'upload HTTP (en-tête `Authorization: Bearer`), à la place du PIN.
5. Le PC accepte indifféremment le PIN ou le token fort (comparaison à temps constant).
   En cas d'identifiant invalide → `HELLO_ACK {accepted:false, reason:"unpaired"}` puis
   **fermeture** de la socket.

Une caméra non appairée ne peut donc **pas** déclencher la capture (ENF-06). L'opérateur
peut **révoquer** l'appairage côté PC (régénère PIN + token ⇒ anciens identifiants invalidés).

### 3.2 Chiffrement (ENF-07, Should)

Le canal peut fonctionner en clair (`ws`/`http`) pour le développement, ou en
**`wss`/`https` avec un certificat auto-signé** généré localement par le PC (option
`--tls`). Le TXT mDNS `tls=1` informe la caméra qui bascule alors en `wss`/`https`.
Le certificat auto-signé est accepté côté caméra après appairage (TOFU).

---

## 4. Messages du canal de commandes

> Jeu de messages minimal exigé par **EF-22** : `START_RECORDING`, `STOP_RECORDING`,
> `SET_SETTINGS`, `STATUS`, `ACK` (+ `SOUND_TRIGGERED`, `HELLO`, `HELLO_ACK`).

### 4.1 `HELLO` — caméra → PC
Première trame après connexion. Identifie et authentifie la caméra.
```json
{
  "type": "HELLO",
  "protocol": 1,
  "token": "<token d'appairage>",
  "device": {
    "id": "a1b2c3d4",
    "name": "Pixel 7 - Salon",
    "platform": "android",
    "model": "Pixel 7",
    "manufacturer": "Google",
    "app_version": "1.0.0"
  }
}
```

### 4.2 `HELLO_ACK` — PC → caméra
```json
{ "type": "HELLO_ACK", "accepted": true, "server_name": "Salon PC", "protocol": 1,
  "token": "<token fort à mémoriser>" }
```
- `token` (présent uniquement si `accepted == true`) : identifiant fort que la caméra doit
  mémoriser et réutiliser ensuite (WebSocket + upload), à la place du PIN.

En cas de rejet : `{ "type": "HELLO_ACK", "accepted": false, "reason": "unpaired" }`.

### 4.3 `START_RECORDING` — PC → caméra (EF-05, UC-02)
```json
{ "type": "START_RECORDING", "origin": "manual", "request_id": "a1b2c3",
  "timestamp": "2026-06-21T14:32:10Z", "max_duration_s": 0 }
```
- `origin` : toujours `"manual"` pour cette commande (le son est local à la caméra).
- `max_duration_s` : 0 = jusqu'à `STOP_RECORDING`, sinon arrêt automatique après N s.

### 4.4 `STOP_RECORDING` — PC → caméra (EF-15, UC-03)
```json
{ "type": "STOP_RECORDING", "request_id": "d4e5f6" }
```

### 4.5 `SET_SETTINGS` — PC → caméra (EF-10, EF-18, UC-05)
Tous les champs sont optionnels ; seuls les champs présents sont appliqués.
```json
{ "type": "SET_SETTINGS", "request_id": "g7h8i9",
  "threshold": 0.35,
  "video_quality": "HD_720P",
  "active_hours": { "start": "22:00", "end": "06:00", "enabled": true },
  "use_classifier": true,
  "target_labels": ["Speech", "Dog", "Glass"],
  "transfer_mode": "deferred" }
```
- `threshold` : 0.0–1.0 (amplitude normalisée ou confiance du classifieur).
- `video_quality` : `SD_480P` | `HD_720P` | `FHD_1080P`.
- `transfer_mode` : `deferred` (upload après enregistrement).

### 4.6 `SOUND_TRIGGERED` — caméra → PC (UC-01)
Notifie qu'un enregistrement **automatique** vient de démarrer.
```json
{ "type": "SOUND_TRIGGERED", "timestamp": "2026-06-21T14:32:10Z",
  "confidence": 0.82, "sound_label": "Glass", "recording_id": "rec_20260621_143210" }
```

### 4.7 `STATUS` — caméra → PC (EF-13, ENF-10)
Émis périodiquement (heartbeat, ~2 s) et à chaque changement d'état.
```json
{ "type": "STATUS", "state": "idle", "recording": false,
  "battery_level": 0.74, "recording_id": null, "timestamp": "2026-06-21T14:32:10Z" }
```
- `state` : `idle` | `listening` | `recording` | `transferring`.

### 4.8 `ACK` — caméra → PC (EF-06, ENF-04)
Réponse à toute commande portant un `request_id`.
```json
{ "type": "ACK", "request_id": "a1b2c3", "result": "ok", "reason": null,
  "recording_id": "rec_20260621_143210" }
```
- `result` : `ok` (exécuté) | `error` (échec) | `received` (reçu, exécution en cours).
- `reason` : message d'erreur lisible si `result == "error"`.

---

## 5. Canal vidéo (HTTP) — EF-09, EF-23

Transfert **différé** : la caméra enregistre localement (EF-08) puis téléverse.

### 5.1 `POST /upload`
- `Content-Type: multipart/form-data`
- En-tête `Authorization: Bearer <token d'appairage>` (même token que le WebSocket).
- Parties :
  - `metadata` (application/json) :
    ```json
    { "recording_id": "rec_20260621_143210", "device_id": "a1b2c3d4",
      "origin": "sound", "sound_label": "Glass",
      "started_at": "2026-06-21T14:32:10Z", "duration_s": 12.4,
      "width": 1280, "height": 720, "mime": "video/mp4" }
    ```
  - `file` (video/mp4) : le fichier vidéo.
- Réponse `200` : `{ "stored": true, "recording_id": "...", "bytes": 1234567 }`.
- Idempotent : un `recording_id` déjà stocké renvoie `{ "stored": true, "duplicate": true }`.

La caméra réessaie l'upload avec backoff tant que la réponse n'est pas `200` (EF-08, ENF-05).

---

## 6. Gestion de la concurrence (EF-07)

Le déclenchement sonore et le déclenchement manuel aboutissent à **la même action**. Si
un enregistrement est déjà en cours, une nouvelle demande (quelle que soit l'origine) :
- **prolonge** l'enregistrement courant (réinitialise `max_duration_s`) **sans** ouvrir un
  second fichier ;
- l'`ACK` renvoie le `recording_id` de l'enregistrement déjà actif.

Les déclenchements sont donc **idempotents** par enregistrement actif.

---

## 7. Résilience (ENF-04, ENF-05)

- Toute commande sans `ACK` dans un délai (par défaut 5 s) est signalée **échec** côté PC.
- La caméra conserve les enregistrements localement et les téléverse à la reconnexion.
- Le PC marque la caméra **hors-ligne** si aucun `STATUS` n'est reçu pendant > 6 s.

---

## 8. Versionnage

Champ `protocol` (entier). Version courante : **1**. Toute évolution incompatible
incrémente ce numéro ; `HELLO_ACK` peut rejeter une version non supportée
(`reason: "protocol_mismatch"`).
