# Organisation du dépôt

Tesla Tempo utilise un dépôt commun pour l'application Android et son relais, avec un dossier autonome par composant.

```text
TeslaTempo/
├── apps/
│   └── android/          # Application Kotlin/Compose
├── services/
│   └── relay/
│       ├── relay/        # Code Python du relais
│       ├── tests/        # Tests du relais
│       ├── Dockerfile
│       ├── .dockerignore
│       └── requirements*.txt
├── deploy/               # Exemples réseau et hébergement
├── scripts/              # Préparation et contrôle de l'instance
├── docs/                 # Spécification et guides transversaux
├── .github/              # CI, modèles d'issues et de PR
├── compose.yaml          # Point d'entrée du déploiement
├── .env.example          # Configuration sans secrets
├── README.md
├── CONTRIBUTING.md
├── CHANGELOG.md
└── LICENSE
```

## Règles de placement

- **Code, tests et dépendances** restent ensemble dans leur composant. Les fichiers Gradle et leur wrapper sont dans `apps/android/` ; les bibliothèques Python du relais restent dans `services/relay/`.
- **Dockerfile et contexte de construction** appartiennent au service qu'ils empaquettent. L'image ne construit pas tout le dépôt.
- **Compose à la racine** décrit l'assemblage et le démarrage de l'instance. Les variantes d'hébergement et de reverse proxy vont dans `deploy/`.
- **Scripts opérateur** restent dans `scripts/`, avec une exécution depuis la racine. Les outils propres au développement d'un composant pourront rester dans ce composant.
- **Documentation métier et d'installation** va dans `docs/`. Un petit README dans chaque dossier aide à trouver sa responsabilité.
- **Aucun dossier partagé artificiel** pour l'instant : un contrat API ou du code commun sera ajouté uniquement lorsqu'il sera effectivement utilisé par plusieurs composants.

## Données locales

`.env`, `secrets/` et `data/` restent à la racine, exclus du dépôt. La réorganisation ne les déplace pas et ne régénère aucune clé. Les environnements `.venv/` et les produits de compilation restent également locaux.

## Commandes et répertoires

| Opération | Répertoire courant | Commande |
| --- | --- | --- |
| Préparer l'instance | Racine | `python3 scripts/init-local.py` |
| Démarrer | Racine | `docker compose up -d --build --wait` |
| Vérifier le mode mock | Racine | `python3 scripts/check-running.py` |
| Installer les dépendances de test | `services/relay/`, environnement virtuel actif | `pip install -r requirements-dev.txt` |
| Tester le relais | `services/relay/`, environnement virtuel actif | `python -m pytest -q` |
| Construire uniquement l'image | Racine | `docker build -t teslatempo-relay:0.2.0 services/relay` |

| Compiler et tester Android | `apps/android/` | `./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug` |

La CI suit les mêmes chemins. Le composant Android regroupe notamment `planner/` (calcul), `tempo/` (données publiques) et `personal/` (profil, calendrier et stockage). Voir le [guide Android](../apps/android/README.md) pour le SDK, l'émulateur et la connexion au relais.
