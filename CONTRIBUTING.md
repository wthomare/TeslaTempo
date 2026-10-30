# Contribuer à Tesla Tempo AI

Le relais Python/Docker V1 et le [socle Android](apps/android/README.md) sont disponibles. Les instructions de démarrage et de test sont dans [le guide du relais](docs/relay-v1.md). Avant de modifier le relais, se placer dans `services/relay/`, installer `requirements-dev.txt` dans un environnement virtuel et exécuter `python -m pytest -q`. La [carte du dépôt](docs/repository.md) précise où placer chaque contribution.

## Proposer une modification

1. Lire le [README](README.md) et le [cahier des charges](docs/specification.md).
2. Ouvrir une issue pour discuter d'une évolution importante ou décrire un problème reproductible.
3. Préparer une branche avec une modification ciblée.
4. Ouvrir une pull request indiquant le problème, le résultat attendu et les vérifications réalisées.

Les échanges et la documentation sont rédigés en français. Conserver une distinction explicite entre exigences, résultats de simulation et capacités validées sur véhicule réel.

## Règles de conception

- Préserver le moteur déterministe et la validation explicite des actions.
- Respecter la politique F20 : cible de 80 % par défaut, départ indicatif, complément HP possible et coût visible.
- Ne jamais supposer qu'un plan calculé est représentable par la programmation Tesla.
- Respecter les programmations manuelles et signaler les états incertains après une erreur.
- Garder les intégrations simulables ; les tests automatisés ne doivent pas commander un véhicule réel.
- Documenter les limites des données et les coûts estimés.

## Secrets et données personnelles

Ne committer aucun secret, clé privée, fichier de configuration personnel ou export de données réel. Les exemples doivent utiliser des valeurs fictives. Les secrets sont injectés à l'exécution, jamais dans l'image ou l'APK.

Avant de partager un journal, retirer notamment les en-têtes d'authentification, codes OAuth, jetons, VIN, coordonnées et informations de compte. Les règles d'exclusion Git et Docker sont une aide ; elles ne remplacent pas la revue du contenu.

## Vérifications avant une pull request

- Vérifier les liens relatifs et la cohérence du README avec la documentation modifiée.
- Exécuter les tests pertinents lorsqu'ils existent et préciser ce qui n'a pas pu être vérifié.
- Pour une affirmation sur une API, citer la source officielle et dater la vérification.
- Ne pas déclarer une intégration opérationnelle sur la seule base d'une documentation ou d'un mock.

Les contributions suivent la [licence Apache 2.0 du dépôt](LICENSE).

## Confidentialité des contributions

La documentation publique décrit le produit et des procédures génériques. Ne pas
y consigner les actions, horaires, déplacements, véhicule personnel, adresses réseau
ou résultats d'installation d'un utilisateur. Utiliser `private/` (ignoré par Git)
pour les notes locales ; les exemples publics doivent être fictifs.

Ajouter un fichier déjà suivi à `.gitignore` ne supprime ni son contenu publié ni
son historique. Vérifier le diff avant chaque commit et utiliser une identité Git
publique, sans adresse professionnelle ou nom de poste.
