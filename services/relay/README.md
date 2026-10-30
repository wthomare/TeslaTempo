# Relais Tesla Tempo

Service Python conteneurisé, version `0.1.0`. Il protège les secrets, publie la clé publique et fournit OAuth et les lectures Tesla. Le mode par défaut est simulé ; aucune commande véhicule n'est exposée.

## Contenu

| Chemin | Responsabilité |
| --- | --- |
| `relay/` | API HTTP, configuration, client Tesla et stockage chiffré |
| `tests/` | Tests avec transport Tesla simulé |
| `Dockerfile` | Construction de l'image du service |
| `.dockerignore` | Exclusions du contexte de construction |
| `requirements.txt` | Dépendances d'exécution figées |
| `requirements-dev.txt` | Dépendances supplémentaires des tests |

## Développer et tester

Depuis ce dossier :

```bash
python3 -m venv .venv
.venv/bin/pip install -r requirements-dev.txt
.venv/bin/python -m pytest -q
```

## Construire l'image

Depuis ce dossier :

```bash
docker build -t teslatempo-relay:0.2.0 .
```

Le contexte Docker est limité à ce service : l'application Android et les secrets de déploiement à la racine ne sont pas envoyés au moteur de construction.

Pour démarrer l'ensemble, revenir à la racine et utiliser [compose.yaml](../../compose.yaml). Les scripts d'exploitation sont dans [scripts/](../../scripts/README.md). Le [guide V1](../../docs/relay-v1.md) décrit HTTPS, OAuth, les secrets et TrueNAS.
