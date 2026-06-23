# Modèle de classification audio (optionnel) — EF-04

Pour activer la détection par **catégorie de son** (plutôt que par simple seuil
d'amplitude), déposez ici le modèle **YAMNet** au format TensorFlow Lite :

```
app/src/main/assets/yamnet.tflite
```

Modèle officiel (gratuit, open source) :
- https://www.kaggle.com/models/google/yamnet/tfLite
- ou https://storage.googleapis.com/mediapipe-models/audio_classifier/yamnet/float32/latest/yamnet.tflite

Sans ce fichier, l'application fonctionne en mode **amplitude** (le détecteur retombe
automatiquement sur le seuil d'énergie sonore). Activez le classifieur via le réglage
« Utiliser le classifieur audio » (poussé par le PC) une fois le modèle présent.

Le fichier n'est pas versionné dans ce dépôt pour rester léger ; le build le prend en
compte automatiquement s'il est présent (`noCompress "tflite"`).
