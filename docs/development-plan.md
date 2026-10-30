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

## Plan de travail avant livraison du véhicule

| Chantier | Livré et testable sans véhicule | Ce qui reste dépendant du véhicule |
| --- | --- | --- |
| Commandes | Bibliothèque de préparation, confirmation à usage unique, journal chiffré, transport TLS à liste fermée, relecture stricte | Lecteur Fleet normalisé à partir de réponses validées, clé virtuelle, branchement API/Android et activation encadrée |
| Erreurs | Simulateur sans réseau : sommeil, débranchement, conflit, timeout, refus partiel, relecture incohérente ; tests de concurrence et redémarrage | Comportements réels et effets de l'ordre des commandes |
| Parcours Android | Diagnostic explicite du relais sans lecture du véhicule ; statut de session et version ; distinction données fictives/réelles | Vérification des scopes et des capacités de la voiture |
| Notifications et distribution | Rappel local facultatif, permission contextuelle, annulation/restauration ; script de signature durable et vérification APK | Validation des restrictions batterie propres au téléphone ; pas de publication automatique |
| Historique | Relevés chiffrés, indicateurs de couverture et baisse de compteur, export CSV sans agrégation trompeuse | Sessions complètes, factures et coûts réels à partir de données attestées |

La chaîne de commandes reste **hors production**. Le lecteur de confiance est un
contrat testé avec des fixtures, pas une preuve de lecture du véhicule. L'aperçu
Android demeure sans bouton d'application. Aucun scope ni réglage personnel n'est
modifié par une compilation ou une mise à jour.

Détails : [orchestration des commandes](command-orchestration.md),
[distribution et rappels Android](android-distribution.md),
[relevés et export](charge-observations.md).

## Ordre de validation avant activation

1. Vérifier les tests Python, Android JVM, lint et les parcours sur émulateur.
2. Construire le relais et l'APK signé ; vérifier signature et identité de mise à jour.
3. À réception du véhicule, vérifier les données, droits et clé virtuelle.
4. Brancher le lecteur normalisé, la préparation serveur et la confirmation Android.
5. Faire un seul essai explicitement confirmé, relire, puis observer la recharge.
6. Valider reprise, conflits et coût API avant tout élargissement fonctionnel.

ChatGPT explique les résultats du moteur ; il n'a aucun outil de commande.
Les relevés ne sont jamais transformés en fausses sessions ni les estimations en
factures. Les coordonnées, activités, clés de signature et paramètres d'une
installation restent hors du dépôt public.

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
