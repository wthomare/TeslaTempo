# Android 0.12.0 — Conduite en BLE (expérimental)

Première version du cockpit local, sans Fleet API ni repli payant pour Conduite.
Installer l'APK signée par-dessus la 0.11.0 conserve le relais et le profil existants.

- Nouvel onglet **Conduite**, aperçu explicitement fictif et réglages d'affichage.
- Appairage local d'une clé Tesla **Vehicle Monitor**, confirmé physiquement dans la voiture.
- Lecture chiffrée BLE de la vitesse, batterie, autonomie et destination lorsqu'elles sont disponibles.
- Ouverture explicite de Waze, avec deux petits blocs superposés et arrêt depuis la notification.
- Permissions nouvelles : Bluetooth scan/connexion, service de premier plan connecté et superposition.
  Pas de permission GPS, pas de service d'accessibilité, pas de changement sur le NAS.

**La connexion à une Tesla réelle reste à valider.** Les tests logiciels ne prouvent
pas la compatibilité du firmware ou la fiabilité de la radio. Faire l'appairage et
les premières comparaisons à l'arrêt ; ne pas utiliser le cockpit comme compteur principal.

[Instructions complètes, limites et parcours d'essai](https://github.com/wthomare/TeslaTempo/blob/main/docs/conduite-ble.md).
