# Installation & exécution

Toutes les technologies sont **gratuites et open source**. Le système fonctionne **100 %
en local** ; placez le PC et les téléphones sur le **même réseau Wi-Fi**.

## 1. Application de réception (PC)

### Prérequis
- Python **3.10+** (Windows, macOS ou Linux).

### Installation & lancement
```bash
cd pc-receiver

# Linux / macOS
./scripts/run.sh --name "Salon PC"

# Windows
scripts\run.bat --name "Salon PC"
```
Le script crée un environnement virtuel, installe les dépendances
(`Flask`, `flask-sock`, `simple-websocket`, `zeroconf`, `cryptography`) et démarre le
serveur. La console affiche l'URL du tableau de bord (ex. `http://192.168.1.20:8766/`) et
le **code PIN d'appairage**.

Options utiles : `--port`, `--tls` (chiffrement), `--data-dir`. Voir `pc-receiver/README.md`.

### Tests
```bash
cd pc-receiver && . .venv/bin/activate && pip install pytest && pytest -q
```

### Empaqueter en exécutable autonome
```bash
cd pc-receiver && ./scripts/build_executable.sh   # -> dist/soundcam-receiver
```

## 2. Application caméra Android

### Prérequis
- **JDK 17** et le **SDK Android** (API 34). Android Studio recommandé, ou SDK CLI.
- Appareil Android **10+** (ENF-11) avec caméra et micro.

### Build
```bash
cd android
echo "sdk.dir=/chemin/vers/Android/Sdk" > local.properties
./gradlew assembleDebug         # -> app/build/outputs/apk/debug/app-debug.apk
./gradlew installDebug          # installe sur l'appareil branché (USB, débogage activé)
```
Détails et option classifieur YAMNet : `android/README.md`.

## 3. Application caméra iOS (périmètre allégé)

### Prérequis
- **macOS + Xcode 15+**, compte développeur Apple (Team ID) pour un appareil réel.
- [XcodeGen](https://github.com/yonatankarp/XcodeGen) : `brew install xcodegen`.

### Build
```bash
cd ios
xcodegen generate               # crée SoundCam.xcodeproj
open SoundCam.xcodeproj          # Run sur un iPhone réel (caméra requise)
```
Renseignez `DEVELOPMENT_TEAM` dans `project.yml` ou via Xcode > Signing. Voir `ios/README.md`.

## 4. Première mise en route (appairage)

1. Lancez l'app PC → notez le **PIN** affiché.
2. Sur le téléphone : autorisez caméra/micro/notifications, onglet **Réglages**, saisissez
   le PIN, **Appairer**. Le PC est découvert automatiquement (mDNS/Bonjour).
3. Onglet **Statut** → **Démarrer**. La caméra apparaît en ligne dans le tableau de bord PC.
4. Depuis le PC : **Déclencher**/**Arrêter** à distance, régler le seuil/qualité, consulter
   la **bibliothèque** des vidéos et le **journal** d'événements.

## 5. Dépannage

| Symptôme | Piste |
|----------|-------|
| La caméra ne trouve pas le PC | Même réseau Wi-Fi ? mDNS bloqué (réseau « invité »/isolation AP) ? Saisir l'IP du PC manuellement dans Réglages. |
| Appairage refusé | PIN erroné ou régénéré côté PC. Ressaisir le PIN courant. |
| Service Android tué en arrière-plan | Désactiver l'optimisation batterie (EF-11) ; voir la carte d'aide dans l'app et https://dontkillmyapp.com. |
| iOS n'enregistre pas en arrière-plan | Limitation système attendue (§7.2) : garder l'app au premier plan. |
| TLS : avertissement certificat | Certificat auto-signé local accepté après appairage (TOFU). Normal sur LAN. |
