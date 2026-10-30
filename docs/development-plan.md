# Plan de développement

Ce plan décrit le produit, sans notes sur une installation ou un utilisateur.
Les validations simulées et les validations sur véhicule réel restent distinctes.

| Lot | Livrable | Validation attendue | Dépendance |
| --- | --- | --- | --- |
| 1 — ChatGPT sans clé API | Connexion locale OAuth/OIDC, transfert privé vers le relais, renouvellement, catalogue de modèles, explications dans Android | Refus des mauvais états/signatures/comptes, aucune fuite de jetons, flux incomplet jamais présenté comme réussi, consentement avant envoi | Consentement du titulaire pour un essai réel |
| 2 — Préparation des commandes | Aperçu déterministe du programme, détection des conflits, confirmation liée au plan exact, expiration des données | Cas simulés : horaires, changement d'heure, doublons, erreurs partielles, résultat inconnu après timeout | Schéma Tesla et règles du moteur |
| 3 — Signature et application | Intégration du proxy officiel de signature, lecture préalable, écriture et relecture | Tests simulés puis une recharge encadrée ; préserver les programmes existants | Véhicule, clé virtuelle et consentement |
| 4 — Historique réel | Collecte bornée et dédoublonnée, mesures distinctes des estimations, coûts traçables | Interruptions, données manquantes, pagination et redémarrage | Réponses réelles des endpoints disponibles |
| 5 — Android et exploitation | Statuts de connexion, erreurs actionnables, notifications, installation et mise à jour reproductibles | Tests Android, sauvegarde/restauration, absence de secrets dans l'APK et les journaux | Lots précédents |

## Avancement du socle

- Lot 1 : code du relais et Android livré, tests simulés OAuth/inférence et tests
  d'interface. L'authentification et l'inférence réelles restent à valider par compte.
- Lot 2 : [politique de préparation et confirmation hors ligne](charging-preparation.md)
  implémentée et testée. Aperçu natif économique/secours dans Android ; adaptateur
  de commandes et confirmation réelle à réaliser.
- Lot 3 : image du proxy officiel et démarrage TLS hors réseau testés ; intégration
  du transport et validation sur véhicule encore nécessaires.
- Lot 4 : relevés ponctuels chiffrés et consultation Android disponibles ; historique
  de sessions et factures encore à réaliser.
- Lot 5 : améliorations progressives ; notifications et distribution des mises à jour
  restent à réaliser. Les essais sur véhicule ne sont pas remplacés par des simulations.

## Ordre de travail

Commencer par le lot 1, puis préparer le lot 2 hors véhicule. Ne pas activer les
commandes de production comme effet secondaire d'une mise à jour. Les essais réels
nécessitent le véhicule et un plan précis confirmé par son utilisateur.

L'assistant explique les résultats du moteur ; il n'a aucun outil de commande.
Aucune clé API payante ni aucun recours à des identifiants d'autres logiciels ne
sert de solution de repli. Les coordonnées, activités et paramètres d'une
installation restent dans les fichiers privés exclus de Git.

## Critères de livraison du lot 1

- Connexion dans le navigateur du poste via boucle locale, avec PKCE et validation OIDC.
- Identifiants de chaque compte séparés, fichiers privés et écritures atomiques.
- Identité propre au NAS préservée lors de l'import ; renouvellements sérialisés.
- Accès au relais authentifié, taille et fréquence des requêtes bornées.
- Modèles issus du catalogue du compte, jamais un nom imposé au hasard.
- Explication demandée explicitement après aperçu des seules données envoyées.
- Erreurs de quota, déconnexion et interruption sans résultat présenté comme complet.
- Déconnexion locale avec tentative de révocation distante.

Les fonctionnalités effectivement livrées sont indiquées dans le
[CHANGELOG](../CHANGELOG.md) ; cette liste n'est pas une déclaration d'achèvement.
