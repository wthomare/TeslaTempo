# Relais Docker V1 — installation et validation

Version `0.4.0`. Instance personnelle, un compte Tesla, un processus.

## Ce qui est livré

- Image constructible depuis [services/relay/Dockerfile](../services/relay/Dockerfile), exécution non-root, système de fichiers en lecture seule.
- Compose avec stockage persistant, secrets montés par fichiers et contrôle de santé.
- Mode `mock` par défaut : aucune requête Tesla, véhicule fictif clairement identifié.
- Mode `tesla` : enregistrement régional de l'application, OAuth avec retour lié au navigateur, renouvellement sérialisé et stockage chiffré des jetons.
- Lecture de la première page des véhicules et de l'état de charge/programmes, sans réveil automatique.
- Publication de la clé publique P-256 au chemin Tesla officiel.
- Jeton d'accès personnel au relais, cache de lecture de 30 secondes et limite de 60 requêtes authentifiées par minute.

Les commandes véhicule, le proxy de signature, l'historique Supercharger ne sont pas encore implémentés. L’assistant facultatif est décrit dans le [guide ChatGPT](chatgpt.md). Le [socle Android](../apps/android/README.md) peut lire ce relais. Aucun appel de charge n'est exposé. Les tests automatisés simulent Tesla ; l’accès réel doit être validé pour chaque instance. Une image locale est construite ; aucune image n'est publiée dans un registre.

## 1. Essayer localement sans compte Tesla

Prérequis : Docker Engine et le plugin Compose, Python 3 et OpenSSL sur la machine qui prépare les fichiers. Utiliser un compte non-root ayant accès à Docker. Exécuter depuis la racine du dépôt :

```bash
python3 scripts/init-local.py
docker compose config --quiet
docker compose up -d --build --wait
python3 scripts/relayctl.py status
python3 scripts/relayctl.py vehicles
python3 scripts/relayctl.py charge-state --vehicle demo
```

Le script initialise `.env`, `secrets/` et `data/`. Il ne remplace aucun secret existant et n'en affiche aucun. Il génère une paire de clés Tesla locale mais n'effectue ni enregistrement ni appairage. La clé privée n'est pas montée dans le relais V1.

Le statut doit afficher `mode: mock`, `simulated: true` et `vehicle_commands_enabled: false`. La batterie fictive est à 55 %. L'API n'écoute que sur `127.0.0.1:8080` de l'hôte par défaut.

`RELAY_UID` et `RELAY_GID` dans `.env` sont initialisés avec les identifiants du compte qui prépare les fichiers. Ils doivent correspondre au propriétaire des répertoires persistants et des secrets. Ne pas utiliser `chmod 777` pour résoudre un problème d'accès.

Pour arrêter :

```bash
docker compose down
```

Cela conserve les fichiers de `secrets/` et `data/`.

## 2. Déployer sur TrueNAS SCALE

Pour une procédure pas à pas depuis un nouveau poste, suivre le [manuel d’installation TrueNAS](installation-truenas.md).

Utiliser une version de TrueNAS SCALE prenant en charge les applications Docker. Le parcours **Apps / Custom App** permet de déployer le relais ; les conteneurs LXC constituent un mécanisme distinct. Vérifier la compatibilité avec la version installée. [Documentation TrueNAS](https://www.truenas.com/docs/scale/).

Préparer un dataset dédié, par exemple `/mnt/POOL/apps/teslatempo`, avec des sous-répertoires `secrets` et `data` et un compte de service non-root. Les chemins sont des exemples à adapter.

Deux options pour l'image :

1. Construire sur une machine Docker de même architecture que le NAS, puis transférer l'image vers un registre auquel le NAS a accès.
2. Utiliser une VM Linux avec Docker sur SCALE, puis suivre le guide Compose standard dans cette VM.

Pour une Custom App, utiliser `compose.yaml` comme base et :

- Remplacer `image` par la référence d'image réellement disponible sur le NAS et retirer `build` si la construction n'est pas prise en charge par l'interface.
- Remplacer les chemins relatifs des volumes et des fichiers secrets par les chemins absolus du dataset.
- Renseigner les variables non secrètes explicitement dans l'interface ou le YAML : l'interface ne charge pas nécessairement notre `.env`.
- Adapter `user` aux UID/GID du compte propriétaire et vérifier les ACL des datasets.
- Vérifier la prise en charge des secrets Compose ; sinon monter chaque fichier individuellement en lecture seule vers son chemin `/run/secrets/...`. Ne jamais coller sa valeur dans le YAML.
- Publier le port du relais uniquement là où le reverse proxy peut le joindre. Si celui-ci tourne dans un autre conteneur, `127.0.0.1` désigne ce conteneur : utiliser un réseau Docker commun ou l'adresse privée appropriée du NAS.

Référence : [Custom Apps TrueNAS](https://apps.truenas.com/managing-apps/installing-custom-apps/).

### Vérification et surveillance

Suivre les contrôles du [manuel d’installation](installation-truenas.md) et du
[guide HTTPS](https-truenas.md). Chaque installation doit vérifier la santé,
l'authentification et les lectures simulées avant de connecter Tesla.
Les résultats propres à une installation restent dans des notes privées hors Git.

## 3. Mettre HTTPS en place

Conserver `RELAY_MODE=mock` pendant la vérification réseau. Configurer le reverse proxy existant pour servir une origine, par exemple `https://relay.example.com`, avec un certificat valide. Un exemple Caddy est fourni dans [deploy/Caddyfile.example](../deploy/Caddyfile.example).

Définir `PUBLIC_BASE_URL` avec cette origine HTTPS, sans sous-chemin, query string ou identifiants. Ne pas utiliser le domaine d'exemple en production. Recréer le conteneur après modification :

```bash
docker compose up -d --force-recreate --wait
```

Depuis un réseau extérieur, vérifier :

- `https://VOTRE_DOMAINE/healthz` répond sans avertissement de certificat.
- `https://VOTRE_DOMAINE/.well-known/appspecific/com.tesla.3p.public-key.pem` renvoie uniquement la clé publique, sans page de connexion.
- `https://VOTRE_DOMAINE/v1/status` refuse une requête sans authentification avec HTTP 401.

Le proxy doit conserver l'en-tête `Authorization`. Ne pas activer de journalisation des URL complètes sur `/oauth/*`, qui contiennent des codes ou tickets temporaires. Le relais désactive les journaux d'accès Uvicorn et les journaux INFO HTTPX ; un reverse proxy doit être configuré séparément. L'interface d'administration du NAS reste privée.

### Accès depuis Internet derrière une Freebox

Le reverse proxy HTTPS hébergé chez soi et le proxy d'entreprise du poste de développement sont deux éléments différents. Aucun proxy d'entreprise n'est configuré dans Compose ou dans l'image.

1. Réserver l'adresse privée du NAS ou de la VM dans le DHCP de la Freebox.
2. Faire pointer le domaine vers l'adresse publique actuelle de la connexion. Si un enregistrement IPv6 AAAA existe, vérifier aussi son chemin réseau ; ne pas laisser une adresse IPv6 inaccessible détourner les clients.
3. Vérifier que le port TCP public **443** est disponible pour la connexion Freebox. S'il est déjà utilisé par un service existant, configurer ce reverse proxy pour router le domaine/le chemin, sans écraser sa configuration.
4. Rediriger le port public 443 vers le port HTTPS du reverse proxy. Le port interne peut être différent, par exemple 8443 ; il ne doit pas être celui de l'administration TrueNAS. Ne pas rediriger directement vers le relais HTTP 8080.
5. Pour un certificat ACME par défi HTTP, acheminer aussi le port public 80 vers le serveur de validation. Un défi DNS évite ce besoin mais demande une configuration DNS adaptée. Le port 443 reste nécessaire pour l'accès public Tesla.
6. Tester depuis la connexion mobile du téléphone, Wi-Fi désactivé, pour vérifier réellement le chemin extérieur.

Si le port 443 n'est pas disponible dans l'attribution IPv4 de la ligne, vérifier dans l'Espace Abonné Freebox l'option **IPv4 fixe full-stack**, qui donne accès à tous les ports. Ce changement peut attribuer une nouvelle adresse publique : mettre alors le DNS à jour. Il n'est pas nécessaire de demander ce changement si le port fonctionne déjà. Ne pas placer le NAS entier en DMZ.

Sources : [redirection de ports Freebox](https://assistance.free.fr/articles/1395), [IPv4 full-stack](https://assistance.free.fr/articles/1758).

## 4. Activer Tesla en lecture seule

Le [tutoriel Tesla Developer](tesla-developer.md) détaille les démarches dans le portail, les autorisations, la facturation et le consentement.

Cette étape se fait lorsque le domaine est prêt et l'application Tesla Developer approuvée.

1. Configurer les origines et le domaine de l'application dans Tesla Developer selon ses exigences, puis la redirection exacte `https://VOTRE_DOMAINE/oauth/callback`.
2. Autoriser les scopes `openid offline_access vehicle_device_data`. La V1 ne demande pas les scopes de commande, de localisation ou d'historique.
3. Renseigner `TESLA_CLIENT_ID` dans `.env`. Saisir le secret client directement dans `secrets/tesla_client_secret` avec un éditeur local ; aucun secret dans le terminal de discussion, le YAML ou le dépôt.
4. Définir `RELAY_MODE=tesla`, `TESLA_REGION=eu` (ou `na` si approprié) et `PUBLIC_BASE_URL=https://VOTRE_DOMAINE`.
5. Configurer facturation et plafond dans Tesla Developer, puis recréer le conteneur.

Enregistrer explicitement l'application dans la région choisie :

```bash
python3 scripts/relayctl.py register --url https://VOTRE_DOMAINE
```

Cette action appelle l'enregistrement partenaire Tesla, pas une commande véhicule. Elle ne doit pas être exécutée automatiquement à chaque démarrage.

Commencer ensuite la connexion propriétaire :

```bash
python3 scripts/relayctl.py connect --url https://VOTRE_DOMAINE
```

Ouvrir **soi-même** le `launch_url` renvoyé, dans son navigateur, sous deux minutes. Ce lien est temporaire et privé : ne pas le partager. Il établit un cookie HttpOnly/Secure, redirige vers Tesla puis attend un retour dans le même navigateur sous dix minutes. Une tentative interrompue par un redémarrage du relais doit être recommencée. L'écran final ne contient aucun jeton.

Lire les informations :

```bash
python3 scripts/relayctl.py status --url https://VOTRE_DOMAINE
python3 scripts/relayctl.py vehicles --url https://VOTRE_DOMAINE
python3 scripts/relayctl.py charge-state --url https://VOTRE_DOMAINE --vehicle IDENTIFIANT_NUMERIQUE
```

L'identifiant numérique provient de `vehicles`. Un véhicule endormi peut être indisponible ; cette V1 ne le réveille pas. Les lectures peuvent être facturées par Tesla. Les données sont mises en cache 30 secondes ; un statut connecté indique la présence de jetons, pas une vérification en temps réel de leur validité.

Sources vérifiées le 29 octobre 2026 : [OAuth Tesla](https://developer.tesla.com/docs/fleet-api/authentication/third-party-tokens), [jetons partenaire](https://developer.tesla.com/docs/fleet-api/authentication/partner-tokens), [enregistrement partenaire](https://developer.tesla.com/docs/fleet-api/endpoints/partner-endpoints), [lectures véhicule](https://developer.tesla.com/docs/fleet-api/endpoints/vehicle-endpoints).

## Contrat HTTP de la V1

Les routes `/v1/*` exigent `Authorization: Bearer <jeton personnel du relais>`. Le CLI lit ce jeton dans un fichier, sans le placer dans les arguments du processus. Pour le futur client Android, le jeton sera configuré par le propriétaire et conservé dans le stockage protégé du téléphone. La V1 n'est pas un service multi-utilisateur.

| Méthode | Route | Effet |
| --- | --- | --- |
| GET | `/healthz` | Santé locale et version ; aucun appel Tesla |
| GET | `/.well-known/appspecific/com.tesla.3p.public-key.pem` | Clé publique |
| GET | `/v1/status` | Mode, présence d'une connexion et capacités |
| POST | `/v1/tesla/register` | Enregistrement partenaire explicite ; simulé sans effet en mock |
| POST | `/v1/tesla/connect` | Ticket de lancement OAuth ; refusé en mock |
| GET | `/oauth/launch` | Ticket à usage unique, cookie navigateur et redirection Tesla |
| GET | `/oauth/callback` | Validation state + cookie, échange du code, aucun jeton retourné |
| DELETE | `/v1/tesla/connection` | Effacement local des jetons, du cache et des tentatives OAuth |
| GET | `/v1/vehicles` | Liste simplifiée, première page uniquement |
| GET | `/v1/vehicles/{id}/charge-state` | Charge et programmation native, véhicule du compte seulement |

Les erreurs amont sont remplacées par des codes génériques, sans contenu ni secret Tesla. Un HTTP 429 demande d'attendre ; la V1 n'effectue aucun retry automatique. Une révocation ou un jeton invalide exige une nouvelle connexion.

## Secrets, sauvegardes et mise à jour

- `relay_api_token` : accès propriétaire au relais. Le remplacer puis recréer le conteneur pour le révoquer ; mettre à jour les clients.
- `token_encryption_key` : clé Fernet de chiffrement des jetons. Sa perte empêche de déchiffrer la base. Sa rotation avec migration n'est pas implémentée.
- `tesla_client_secret` : secret de l'application Tesla ; vide en mock.
- `tesla_public_key.pem` : seul fichier diffusé publiquement.
- `tesla_private_key.pem` : conservée pour le futur appairage/signature ; non montée dans cette V1.
- `data/relay.sqlite3` : jetons chiffrés et journal borné de 1 000 événements sans paramètres ni données véhicule.

Arrêter le relais avant de sauvegarder `data/`, `.env` et `secrets/`. Stocker la sauvegarde chiffrée, avec accès limité. Restaurer ensemble la base et sa clé de chiffrement, puis rétablir les UID/GID et permissions. Les secrets Compose sont des fichiers montés : Compose seul ne chiffre pas ces fichiers sur l'hôte.

Une mise à jour se fait par sauvegarde, récupération de la version choisie, construction et `docker compose up -d --build --wait`. Ne pas supprimer les répertoires persistants. Exécuter une seule instance et un seul worker ; pas de réplication avec cette base. Les migrations de schéma seront documentées lorsqu'elles seront nécessaires.

La déconnexion locale ne révoque pas le consentement chez Tesla. Pour une révocation complète, retirer également l'autorisation dans le compte Tesla. Une sauvegarde antérieure peut encore contenir des jetons chiffrés.

## Vérification du développement

```bash
cd services/relay
python3 -m venv .venv
.venv/bin/pip install -r requirements-dev.txt
.venv/bin/python -m pytest -q
cd ../..
docker build -t teslatempo-relay:0.4.0 services/relay
python3 scripts/check-running.py
```

Les 25 tests ne contactent jamais Tesla : ils vérifient auth, OAuth, expiration/rejeu, chiffrement, renouvellement concurrent, contrôle des véhicules, erreurs et limitation de requêtes. Le dernier script suppose Compose déjà démarré en mode mock sur le port local 8080 ; il vérifie le conteneur réellement en cours d'exécution. La vérification Docker utilise uniquement des secrets locaux générés pour cette instance. Le workflow GitHub Actions reprend ces contrôles sans publier d'image ni contacter Tesla.

Avant les commandes réelles, chaque installation doit valider HTTPS, l'enregistrement Tesla et les réponses OAuth/lectures du compte, puis à intégrer le proxy signé et le contrôle des plans. Voir [l'audit](feasibility.md).

Les lectures réelles peuvent conserver des [relevés chiffrés](charge-observations.md), sans collecte permanente ni total de sessions déduit.
