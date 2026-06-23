# Guide utilisateur

## 1. Concept

Transformez un ancien smartphone en **caméra de surveillance** qui enregistre
automatiquement lorsqu'un **son** est détecté, ou **à la demande** depuis votre PC. Tout
reste **chez vous** : les vidéos sont stockées sur le PC, rien ne transite par Internet.

## 2. Côté PC (tableau de bord)

Ouvrez l'URL affichée au démarrage (ex. `http://192.168.1.20:8766/`).

- **Tableau de bord** : liste des caméras (en ligne / hors-ligne / en enregistrement),
  niveau de batterie, boutons **Déclencher** et **Arrêter** par caméra.
- **Bibliothèque** : toutes les vidéos reçues (date, origine *son*/*manuel*, durée). Lecture
  intégrée, **archivage** et **suppression**.
- **Réglages** : seuil de détection, qualité vidéo (480p/720p/1080p), classifieur audio,
  catégories de sons ciblées, plages horaires actives. Les réglages sont **poussés** vers
  les caméras.
- **Événements** : journal horodaté (déclenchements, connexions, transferts…).
- **PIN d'appairage** : affiché en haut ; bouton pour le **régénérer** (révoque les
  appairages existants).

## 3. Côté téléphone

- **Statut** : état du service, connexion au PC, enregistrement en cours, nombre de vidéos
  en attente de transfert.
- **Réglages** : appairage (PIN), nom de la caméra, serveur (optionnel), seuil, qualité,
  classifieur.
- **Démarrer / Arrêter** le service de surveillance.

## 4. Scénarios typiques

### Surveillance par le son (automatique)
1. Placez le téléphone, branchez-le, lancez le service.
2. Un bruit dépasse le seuil → la caméra enregistre, prévient le PC (`SOUND_TRIGGERED`),
   puis téléverse la vidéo. Elle apparaît dans la **Bibliothèque**.

### Vérification manuelle (à distance)
1. Depuis le tableau de bord, cliquez **Déclencher** sur la caméra voulue.
2. La caméra démarre l'enregistrement et confirme (`ACK`). Cliquez **Arrêter** pour finir.

### Réglage à distance
Modifiez le seuil ou la qualité dans **Réglages** (PC) : la caméra applique immédiatement.

## 5. Bonnes pratiques

- **Batterie** : gardez le téléphone branché ; sur certaines marques, désactivez
  l'optimisation de batterie (l'app propose un raccourci, EF-11).
- **Positionnement** : Wi-Fi stable, caméra dégagée, micro non obstrué.
- **Seuil** : augmentez-le si trop de déclenchements ; activez le **classifieur** pour ne
  réagir qu'à certains sons (ex. bris de verre, aboiement).

## 6. Vie privée & cadre légal

Filmer des personnes peut être encadré par la loi. La capture pouvant être **déclenchée à
distance**, utilisez ce système uniquement dans un cadre légal, chez vous, avec le
**consentement** des personnes concernées. Une **notification persistante** (Android) et un
**indicateur d'enregistrement** (iOS) signalent l'activité de la caméra (§9.2).
