# Application caméra iOS (version allégée)

Implémente le **périmètre réduit iOS** défini au §7.2 du cahier des charges. Swift /
SwiftUI / AVFoundation / Network. **100 % local** : communique uniquement avec
l'application de réception PC sur le Wi-Fi local.

## Périmètre (et limites iOS)

| Capacité | Android | iOS (cette app) |
|----------|:------:|:----------------|
| Capture vidéo | arrière-plan | **premier plan uniquement** (restriction système) |
| Écoute sonore | arrière-plan | arrière-plan (mode `audio`) — son signalé au PC |
| Déclenchement manuel (PC) | toujours | premier plan uniquement |
| Transfert différé, appairage, découverte, réglages distants | ✓ | ✓ |

Ces limites sont expliquées à l'utilisateur dans l'onglet **Infos** de l'app.

## Correspondance des exigences

| Exigence | Fichier |
|----------|---------|
| EF-01 capture | `Capture/CameraRecorder.swift` |
| EF-03 détection sonore | `Audio/SoundDetector.swift` (AVAudioEngine, RMS) |
| EF-05/EF-06 déclenchement + ACK | `Net/CommandClient.swift` |
| EF-08/EF-09 transfert différé | `Data/PendingUploads.swift`, `Net/VideoUploader.swift` |
| EF-12 découverte | `Net/Discovery.swift` (Bonjour) |
| Orchestration | `Core/CameraController.swift` |

## Prérequis

- **macOS + Xcode 15+**, un identifiant Apple (Team ID) pour signer sur appareil réel.
- [XcodeGen](https://github.com/yonatankarp/XcodeGen) (open source) pour générer le projet :
  `brew install xcodegen`.

## Génération & build

```bash
cd ios
xcodegen generate            # crée SoundCam.xcodeproj à partir de project.yml
open SoundCam.xcodeproj       # puis Run sur un appareil réel (caméra requise)

# ou en ligne de commande (simulateur, sans caméra) :
xcodebuild -project SoundCam.xcodeproj -scheme SoundCam \
  -destination 'generic/platform=iOS Simulator' build
```

Renseignez votre `DEVELOPMENT_TEAM` dans `project.yml` (ou dans Xcode > Signing) pour un
déploiement sur appareil physique.

## Utilisation

1. Lancez l'app de réception PC, notez le **PIN**.
2. Onglet **Réglages** → saisissez le PIN → *Appairer* (le PC est trouvé via Bonjour).
3. Onglet **Statut** → *Démarrer*. Gardez l'app au premier plan pour la capture.
4. Le PC peut déclencher/arrêter et régler à distance ; les vidéos sont transférées
   automatiquement (reprise après coupure).
