# Application caméra Android

Application de surveillance à **déclenchement sonore et manuel** (module mobile principal).
Kotlin / Jetpack Compose / CameraX / OkHttp. Couvre EF-01 → EF-11 et les ENF associées.
**100 % local** : communique uniquement avec l'application de réception PC sur le Wi-Fi local.

## Fonctions

| Exigence | Implémentation |
|----------|----------------|
| EF-01 capture vidéo | CameraX `VideoCapture` (`capture/VideoRecorder.kt`) |
| EF-02 arrière-plan | Foreground service `camera+microphone+dataSync` (`service/CameraService.kt`) |
| EF-03 détection sonore | `AudioRecord` + énergie RMS (`audio/SoundDetector.kt`) |
| EF-04 classification (option) | YAMNet TFLite (`audio/ClassifierEngine.kt`), repli amplitude |
| EF-05/EF-06 déclenchement manuel + ACK | `net/CommandClient.kt` (WebSocket) |
| EF-07 anti-doublon | Contrôleur d'enregistrement unique (service) |
| EF-08/EF-09 transfert différé | File locale + upload HTTP (`data/PendingUploads.kt`, `net/VideoUploader.kt`) |
| EF-10 réglages locaux | `data/SettingsRepository.kt` (DataStore) |
| EF-11 guidage batterie | `util/BatteryGuidance.kt` |
| EF-12 découverte | NSD/mDNS (`net/Discovery.kt`) |

## Prérequis

- Android Studio (Koala+) ou SDK en ligne de commande, **JDK 17**.
- `compileSdk 34`, `minSdk 29` (Android 10, ENF-11).

## Build

```bash
# Indiquer le SDK Android
echo "sdk.dir=/chemin/vers/Android/Sdk" > local.properties

./gradlew assembleDebug      # -> app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease    # build optimisé (minify/proguard)
```

Installer sur un appareil :

```bash
./gradlew installDebug
# ou
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Utilisation

1. Lancez l'**app de réception PC** ; notez le code PIN affiché.
2. Ouvrez SoundCam, accordez caméra/micro/notifications.
3. Onglet **Réglages** → saisissez le **PIN** → *Appairer*. Le PC est trouvé
   automatiquement par mDNS (sinon renseignez son IP).
4. Onglet **Statut** → *Démarrer*. Une notification persistante indique la surveillance
   (exigée techniquement et pour la transparence légale, §9.2).
5. Le PC peut déclencher/arrêter à distance et modifier les réglages ; les vidéos sont
   transférées automatiquement (reprise après coupure).

### Classification audio (optionnel, EF-04)

Déposez `yamnet.tflite` dans `app/src/main/assets/` puis activez « Classifieur audio »
(voir `app/src/main/assets/README.md`).

## Notes constructeurs (EF-11)

Sur Xiaomi/Huawei/Oppo/Vivo/OnePlus/Samsung, désactivez l'optimisation de batterie et
autorisez le démarrage automatique pour éviter l'arrêt du service. L'app propose des
raccourcis vers les réglages et https://dontkillmyapp.com.
