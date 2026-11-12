# Android 0.13.0 — batterie, autonomie et destination

Le cockpit affiche uniquement la batterie et l’autonomie dans un bloc en haut à droite.
La vitesse est supprimée de l’aperçu, de la superposition et du modèle de données.
Aucune option ne permet de la réactiver.

La batterie et l’autonomie sont lues en BLE toutes les 30 secondes pendant la session.
La destination reste disponible via **Synchroniser la destination Tesla**, puis
**Ouvrir cette destination dans Waze**. La lecture de DriveState est uniquement
ponctuelle, à la demande ; son éventuelle vitesse est ignorée.

Installer l’APK par-dessus la version précédente conserve les paramètres et la clé BLE.
La validation sur voiture réelle reste nécessaire ; aucun changement du NAS.

[Guide BLE](https://github.com/wthomare/TeslaTempo/blob/main/docs/conduite-ble.md).
