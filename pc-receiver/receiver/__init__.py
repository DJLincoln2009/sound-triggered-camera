"""Application de réception PC — Caméra à déclenchement sonore et manuel.

Architecture 100 % locale (aucun cloud). Ce paquet expose :
  - le serveur de commandes WebSocket (canal de pilotage),
  - le serveur HTTP d'upload vidéo et l'interface web de pilotage,
  - la découverte réseau mDNS,
  - le stockage SQLite + système de fichiers,
  - l'appairage et l'authentification des caméras.

Voir ../../protocol/PROTOCOL.md pour le protocole réseau partagé.
"""

__version__ = "1.0.0"
PROTOCOL_VERSION = 1
