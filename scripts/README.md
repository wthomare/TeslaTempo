# Scripts d'exploitation

Ces commandes s'exécutent depuis la racine du dépôt. Elles préparent ou vérifient l'instance Docker locale ; le code du service et ses tests sont dans [services/relay/](../services/relay/README.md).

| Commande | Usage |
| --- | --- |
| `python3 scripts/init-local.py` | Prépare `.env`, `secrets/` et `data/`, sans écraser les secrets existants |
| `python3 scripts/relayctl.py status` | Interroge le relais ; voir `--help` pour les autres opérations |
| `python3 scripts/check-running.py` | Vérifie l'instance mock démarrée sur le port local 8080 |

Les fichiers d'instance restent à la racine et sont exclus de Git. Aucun script n'est nécessaire dans l'image de production. Voir le [guide de déploiement](../docs/relay-v1.md).

## Validation avant véhicule et Android

- `rehearse-commands.py` : scénarios de commandes entièrement fictifs, sans compte ni réseau.
- `android-signing.py init` : créer une identité privée de signature, une seule fois.
- `android-signing.py build` : construire, contrôler et préparer l'APK signé.

Voir [la distribution Android](../docs/android-distribution.md) et
[l'orchestration](../docs/command-orchestration.md). Ne jamais publier la clé de signature.
