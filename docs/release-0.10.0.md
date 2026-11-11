# Version de test Android 0.10.0

Cette version prépare les essais sur Android 13 à 16. La validation locale utilise
un émulateur API 36 ; un essai sur un Pixel 9 physique reste nécessaire. Elle ne
commande pas le véhicule. Le relais 0.5.1 corrige la gestion des refus Tesla ;
les fonctions Android restent compatibles avec le relais 0.5.0.

## Installation sur téléphone

1. Télécharger `teslatempo-release.apk` depuis la release GitHub du projet.
2. Ouvrir le fichier sur le téléphone. Autoriser ponctuellement l'installation
   depuis le navigateur ou l'application Fichiers utilisée.
3. Installer **Tesla Tempo**, puis retirer cette autorisation si elle n'est plus utile.
4. Une mise à jour signée conserve les données. L'ancienne édition de développement
   (`org.teslatempo.debug`) est distincte : renseigner à nouveau le profil et le
   relais dans l'édition de distribution (`org.teslatempo`).
5. Dans Réglages, saisir l'adresse HTTPS du relais et son jeton privé. Le bouton
   de diagnostic vérifie la connexion sans lecture de la voiture. L'enregistrement
   du relais fonctionne même si la session Tesla n'est pas encore connectée.

Le jeton ne figure pas dans l'APK. Ne pas le partager dans une issue ou capture.
L'APK n'est pas distribué via le Play Store et ne se met pas à jour automatiquement.

## Essai conseillé, sans voiture

- Ouvrir la démonstration, simuler puis enregistrer un plan ; le retrouver après
  fermeture et réouverture de l'application.
- Vérifier la lisibilité, le clavier et le défilement avec la navigation gestuelle
  et une taille de texte augmentée sur le Pixel.
- Enregistrer un profil personnel, actualiser Tempo et vérifier la provenance et
  les dates des tarifs. Une estimation reste une estimation.
- Activer le mode avion : vérifier que les erreurs sont compréhensibles et que
  le profil et l'historique restent consultables ; rétablir ensuite le réseau.
- Tester le diagnostic du relais. Une voiture absente/endormie ne doit pas empêcher
  de sauvegarder une connexion au relais valide.
- Pour un plan personnel, tester le rappel et la permission de notification.
  Le rappel n'applique aucun programme et Android peut le retarder.
- En cas de bug : noter l'action, le résultat attendu et observé, puis utiliser
  Réglages → Diagnostics privés. Relire le contenu avant un éventuel envoi.

## Publication et contrôles

La clé de signature reste sur le poste privé. `android-signing.py build` vérifie
l'identité de l'application, le mode release et les permissions avant de préparer
l'artefact. `SHA256SUMS` permet de contrôler le téléchargement ; `SIGNATURE.txt`
identifie le certificat public. L'empreinte SHA-256 attendue du certificat officiel :

```text
cf9640b2484acdfdf1aa6cc381bca534318d1b1fc50d776ffb1dc3d86acc3214
```

Une branche temporaire `apk/*` contient l'APK déjà signé, son empreinte et les
métadonnées de publication dans `.release/`. Le workflow vérifie ces éléments et
crée une préversion GitHub ciblant le commit source parent. Les binaires ne sont
pas ajoutés à `main`, et aucune clé privée n'est transmise à GitHub. Supprimer la
branche temporaire après confirmation de publication des pièces jointes.

La CI contrôle les tests, le lint, les dépendances Python et Maven (OSV), les
constats statiques Python de sévérité moyenne/haute et les frontières des fichiers
privés. Le contrôle de fichiers détecte certains formats de secrets ; il ne
remplace pas une revue. Les audits de dépendances dépendent des bases connues au
moment du contrôle et ne garantissent pas l'absence de vulnérabilités inconnues.

### Validation locale de cette version

- 142 tests Python, 72 tests Android JVM et 19 tests instrumentés API 36 réussis.
- Lint Android debug/release et vérification de la signature APK réussis.
- Aucun avis de vulnérabilité connu détecté dans les dépendances Python et les
  74 modules Maven audités ; aucun constat Bandit de sévérité moyenne ou haute.
- Scénarios de panne/reprise Loki, collecte des événements différés, authentification
  Grafana et absence de port Loki publié vérifiés sur une stack Docker de test.

Ces résultats ne remplacent pas les essais sur téléphone physique et sur véhicule.
Le relais corrigé doit être déployé séparément sur l'installation de l'utilisateur.
