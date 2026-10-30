# Historique des versions

## Android 0.5.0

- Aperçu explicite d'un résumé agrégé à copier ou partager vers ChatGPT depuis une simulation archivée.
- Export sans dates, activités, identifiants ni secrets ; aucun envoi automatique ni commande véhicule.
- Clarification du parcours Tesla Developer avant association d'un véhicule et documentation du choix ChatGPT sans clé API.
- La connexion intégrée Sign in with ChatGPT reste à implémenter.

## Android 0.4.0

- Historique local des simulations personnelles et de démonstration, avec enregistrement explicite.
- Conservation des hypothèses, créneaux, prix et sources sans recalcul des archives.
- Consultation hors connexion, suppression unitaire ou complète avec confirmation.
- Limite de 50 entrées sans suppression automatique ; données exclues des sauvegardes Android.
- Aucune commande véhicule et aucune transmission de l'historique.

## Android 0.3.0 — 29 octobre 2026

- Couleurs Tempo publiées et six tarifs TTC datés via calendrier-tempo.fr, cache et provenance visibles.
- Profil personnel, semaine type et exceptions datées sauvegardés avec DataStore.
- Calcul personnel sur 48 heures avec niveau de batterie saisi et branchement confirmé, séparé du simulateur fictif.
- Validation des sources, dates, erreurs partielles et horaires de mobilité ; aucune commande Tesla.

## Android 0.2.0 — 29 octobre 2026

- Moteur Tempo/F20 déterministe : disponibilités, coûts versionnés, HP rouge, inconnues et changements d’heure.
- Comparaison plan économique / charge continue de secours, objectifs partiels explicites.
- Simulateur à tarifs fictifs, aucune commande Tesla ; thème sombre anthracite.

## Android 0.1.0 — 29 octobre 2026

- Socle Kotlin/Compose, démonstration hors connexion et tableau de bord en lecture seule.
- Connexion au relais V1, URL HTTPS, jeton chiffré Android Keystore et erreurs réseau explicites.
- Wrapper Gradle, tests réseau et instrumentés, CI de compilation et guide Android.
- Tempo, planification et commandes véhicule restent à développer.

## 0.1.0 — 29 octobre 2026

Organisation : relais regroupé dans `services/relay/`, emplacement Android dans `apps/android/`, cahier des charges dans `docs/specification.md`. Compose reste à la racine.

Première version du relais personnel, en lecture seule.

- Dockerfile, Compose, secrets par fichiers et stockage persistant chiffré.
- Démonstration sans accès Tesla, publication de clé publique et API authentifiée.
- OAuth Tesla, renouvellement des jetons et enregistrement partenaire explicite.
- Lecture des véhicules et de l'état de charge, sans commande ni réveil automatique.
- CLI d'exploitation, 25 tests automatisés et contrôle du conteneur en mode mock.
- Guide TrueNAS SCALE et réseau domestique Freebox.

Validation : tests simulés et construction/exécution Docker locales. L'accès Tesla réel, le déploiement NAS et HTTPS public restent à valider. Pas d'APK, de proxy de commandes signées ou d'image publiée dans un registre à cette étape.
