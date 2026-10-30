# Installer et reprendre le relais sur TrueNAS

Ce manuel permet de gérer le NAS depuis un autre poste ou de refaire une installation.
Le guide cible TrueNAS SCALE avec prise en charge des applications Docker et le relais **0.1.0**. Les noms de menus peuvent évoluer.
Les adresses, chemins et UID ci-dessous sont des exemples à adapter ; aucun secret
ni réglage personnel n'est fourni dans Git.

## Quelle interface utiliser ?

Le relais est un service HTTP destiné à l'application Android. Il n'a pas de
page d'accueil ni de tableau de bord web : un HTTP 404 sur `/` est normal.

- **Application Android** : consulter les données et préparer les plans.
- **TrueNAS → Apps → teslatempo** : démarrer, arrêter, consulter les journaux et modifier le YAML.
- **SSH et scripts du dépôt** : installer, diagnostiquer, sauvegarder et lancer la connexion Tesla.
- **Navigateur** : autoriser l'accès au compte sur le site Tesla lors d'OAuth.

Une interface web dédiée n'est pas nécessaire pour cette V1. Le contrôle `/healthz`
renvoie du JSON ; il ne constitue pas une interface utilisateur.

## 1. Préparer le nouveau poste

Utiliser Linux, macOS ou WSL sous Windows, avec Git et OpenSSH (`ssh`, `scp`,
`ssh-keygen`). Docker et Android Studio ne sont pas nécessaires sur ce poste :
la construction se fera sur le NAS. Le poste doit joindre le réseau local du NAS,
ou un VPN donnant accès à ce réseau.

Sur le **poste**, créer une clé dédiée ; choisir une phrase secrète lorsque proposée :

```bash
ssh-keygen -t ed25519 -f ~/.ssh/teslatempo_nas -C teslatempo-nas
cat ~/.ssh/teslatempo_nas.pub
```

Si ce fichier existe déjà, ne pas l'écraser. Dans TrueNAS, modifier le compte
administrateur sous **Credentials → Users**, autoriser son accès SSH et ajouter
la nouvelle clé **publique** sans supprimer les clés encore utilisées. Activer
le service SSH sous **System → Services** : le démarrage automatique seul ne le lance pas.
L'authentification SSH par mot de passe n'est pas nécessaire.

Ajouter un bloc dans `~/.ssh/config` sur le poste, en remplaçant l'adresse et le compte :

```sshconfig
Host teslatempo-nas
    HostName 192.0.2.25
    User COMPTE_ADMIN
    IdentityFile ~/.ssh/teslatempo_nas
    IdentitiesOnly yes
    Port 22
```

```bash
ssh teslatempo-nas
```

Au premier accès, vérifier l'empreinte de la clé du serveur auprès du NAS avant de
l'accepter. Si le port refuse la connexion, vérifier que SSH est démarré et son port.
Un délai dépassé invite à vérifier la route, le VPN et le pare-feu. Ne pas ouvrir
le port SSH sur la box pour un accès depuis le réseau local.

Les commandes `sudo` suivantes peuvent demander le mot de passe du compte dans
le terminal. Ne pas écrire ce mot de passe dans une commande, un fichier Git ou une discussion.

## 2. Si l'application existe déjà : reprendre sans réinstaller

Sur le **NAS**, vérifier :

```bash
sudo docker ps --filter name=ix-teslatempo
sudo docker inspect --format '{{.State.Status}} / {{.State.Health.Status}}' ix-teslatempo-relay-1
curl --noproxy '*' --fail http://127.0.0.1:18080/healthz
```

Si `teslatempo` existe dans **Apps**, conserver son dataset, ses clés et ses données.
Le changement de poste ne nécessite ni reconstruction ni nouvelle paire de clés Tesla.
La nouvelle clé SSH sert uniquement à l'administration du NAS.

Retrouver le chemin du stockage dans le YAML de l'application, section `volumes`
et `secrets`. Passer ensuite aux sections vérification, HTTPS ou maintenance de ce manuel.

## 3. Nouvelle installation : préparer le stockage

Dans TrueNAS, vérifier que **Apps** dispose déjà d'un pool et que Docker fonctionne :

```bash
sudo docker version
sudo docker compose version
uname -m
id
```

Ne pas réinstaller Docker ni changer le pool des applications existantes.
Créer dans **Datasets** un dataset dédié `POOL/teslatempo` avec droits Unix privés
pour le compte qui prépare les fichiers. Le chemin devient `/mnt/POOL/teslatempo`.
Le compte doit être non-root ; relever son UID et son GID avec `id`.

Dans les commandes suivantes, remplacer `POOL` par le vrai nom du pool. Sur le NAS,
pour un dataset **nouveau et réservé à TeslaTempo**, affecter son propriétaire :

```bash
sudo chown "$(id -u):$(id -g)" /mnt/POOL/teslatempo
sudo chmod 700 /mnt/POOL/teslatempo
mkdir /mnt/POOL/teslatempo/source
```

Ne pas appliquer ces changements au pool entier ou à un dataset partagé.
Si des ACL héritées empêchent l'accès, les corriger sur le seul dataset dédié dans
TrueNAS ; ne pas utiliser `chmod 777`.

## 4. Transférer les sources et construire l'image

Sur le **poste** :

```bash
git clone https://github.com/wthomare/TeslaTempo.git
cd TeslaTempo
git rev-parse HEAD
git archive --format=tar.gz --output=/tmp/teslatempo-source.tar.gz HEAD
scp /tmp/teslatempo-source.tar.gz teslatempo-nas:/mnt/POOL/teslatempo/source.tar.gz
```

Noter le commit pour pouvoir reconstruire la même version. `git archive` n'embarque
que les fichiers suivis dans Git, pas les secrets locaux ou les fichiers ignorés.
Pour une version choisie, faire `git checkout IDENTIFIANT_DU_COMMIT` avant l'archive.

Sur le **NAS**, dans le répertoire neuf :

```bash
cd /mnt/POOL/teslatempo/source
tar -xzf ../source.tar.gz
python3 scripts/init-local.py
sudo docker build -t teslatempo-relay:0.3.0 services/relay
```

Ne pas lancer `init-local.py` avec `sudo`. Il génère `secrets/`, `data/` et `.env`
sans afficher les secrets ni remplacer ceux qui existent. La clé privée Tesla ne
sera pas montée dans le relais. Le NAS doit pouvoir télécharger l'image Python
et les dépendances ; le proxy éventuel du poste n'est pas transmis au NAS.

## 5. Installer l'application TrueNAS

Prendre comme base [deploy/truenas-compose.example.yaml](../deploy/truenas-compose.example.yaml).
Dans TrueNAS : **Apps → Discover Apps → Install via YAML** (menu à trois points),
nommer l'application **teslatempo**, puis coller le YAML adapté.

Avant l'installation :

1. Remplacer chaque `/mnt/POOL/teslatempo/source` par le chemin réel.
2. Remplacer `user: "1000:1000"` par l'UID et le GID relevés à l'étape 3.
3. Conserver `RELAY_MODE: mock` pour les premiers tests.
4. Vérifier que le port local `18080` est libre ; adapter les commandes si modifié.
5. Conserver `pull_policy: never` : l'image vient de la construction locale.

Ce YAML contient des chemins vers les secrets, jamais leur contenu. Il utilise
les contrôles de santé intégrés au Dockerfile et limite les journaux.
Enregistrer aussi une copie du YAML réellement utilisé, nommée
`truenas-compose.yaml`, dans le répertoire d'instance sur le NAS, hors dépôt public.

**Dans ce parcours, le YAML de l'application TrueNAS fait autorité.** Le `.env`
généré par le script sert au parcours Compose classique ; le modifier ne change
pas la Custom App. Ne pas lancer `docker compose up` à côté de l'application
TrueNAS : cela créerait une deuxième instance sur les mêmes données.

La procédure correspond au mécanisme officiel [Custom Apps / YAML](https://apps.truenas.com/managing-apps/installing-custom-apps/).

## 6. Vérifier le fonctionnement

Attendre l'état **RUNNING** dans Apps. Sur le **NAS** :

```bash
cd /mnt/POOL/teslatempo/source
sudo docker inspect --format '{{.State.Status}} / {{.State.Health.Status}}' ix-teslatempo-relay-1
curl --noproxy '*' --fail http://127.0.0.1:18080/healthz
curl --noproxy '*' --silent --output /dev/null --write-out '%{http_code}\n' http://127.0.0.1:18080/v1/status
python3 scripts/relayctl.py status --url http://127.0.0.1:18080
python3 scripts/relayctl.py charge-state --url http://127.0.0.1:18080 --vehicle demo
sudo docker logs --tail 50 ix-teslatempo-relay-1
```

Résultats attendus : `running / healthy`, santé `ok`, HTTP **401** sans jeton,
statut `mode: mock` et `vehicle_commands_enabled: false`, données `simulated: true`.
Le CLI lit le jeton depuis son fichier sans l'exposer dans les arguments du processus.
Le script `check-running.py` vise le port 8080 : utiliser les commandes ci-dessus
pour ce déploiement sur 18080.

Pour tester le redémarrage, arrêter puis démarrer **teslatempo uniquement** depuis
Apps et refaire les contrôles. Ne pas redémarrer le NAS entier pour ce test.

Depuis le **poste**, un tunnel permet de consulter la santé sans ouvrir le relais au réseau :

```bash
ssh -N -L 18080:127.0.0.1:18080 teslatempo-nas
```

Laisser ce terminal ouvert et consulter `http://127.0.0.1:18080/healthz` sur le poste.
Ce tunnel n'est pas une exposition HTTPS publique et ne suffit pas aux exigences Tesla.

## 7. HTTPS, Android et connexion Tesla

L'installation initiale écoute sur la boucle locale du NAS. Une adresse telle que
`http://ADRESSE_DU_NAS:18080` ne fonctionne donc pas depuis le téléphone.

Pour les démarches auprès de Tesla, suivre le [tutoriel Tesla Developer](tesla-developer.md).

Le [guide HTTPS TrueNAS et Freebox](https-truenas.md) fournit le YAML complet avec Caddy.

Suivre les sections **HTTPS**, **Freebox** et **Activer Tesla en lecture seule** du
[guide du relais](relay-v1.md). Elles détaillent le domaine, le certificat, les ports,
le chemin de la clé publique, le retour OAuth et les secrets Tesla Developer.

Adapter le reverse proxy à la cible **18080**. Un proxy dans un autre conteneur ne
peut pas atteindre la boucle locale du NAS : prévoir un réseau Docker partagé ou
une adresse privée du NAS avec un port publié adapté. Ne jamais diriger le domaine
public vers l'interface d'administration TrueNAS.

Changer `PUBLIC_BASE_URL` et, plus tard, `RELAY_MODE` et `TESLA_CLIENT_ID` dans le
**YAML de la Custom App**. Le secret client se saisit directement dans son fichier
protégé. Garder la copie locale du YAML synchronisée avec l'application.

Configurer ensuite dans Android l'origine HTTPS et le jeton personnel du relais,
à récupérer depuis `secrets/relay_api_token` par un moyen privé. Ne pas le publier
ni l'insérer dans un QR code accessible à tous. La build Android de production
exige HTTPS ; les exceptions HTTP de debug ne couvrent pas une adresse LAN arbitraire.

## 8. Surveillance et limites

Le contrôle de santé s'exécute toutes les 30 secondes ; les journaux Docker sont
bornés à trois fichiers de 5 Mo. `restart: unless-stopped` relance le processus
s'il s'arrête et permet sa reprise au démarrage de Docker, sauf arrêt explicite.

Un état `unhealthy` seul ne déclenche pas de redémarrage. Aucune notification ni
sonde externe n'est configurée par ce manuel : un NAS éteint ne peut pas signaler
lui-même son indisponibilité. Pour des alertes permanentes, ajouter ultérieurement
une supervision extérieure avec un canal de notification choisi par l'utilisateur.

## 9. Sauvegarder, restaurer et mettre à jour

### Sauvegarder

Arrêter `teslatempo` dans Apps avant de copier la base. Sauvegarder ensemble :

- `data/` et `secrets/`, dont la clé de chiffrement et la paire de clés Tesla ;
- le YAML effectivement déployé, `.env` s'il est utilisé, et l'identifiant du commit ;
- la configuration du reverse proxy et les informations nécessaires à sa restauration.

Utiliser une sauvegarde chiffrée avec accès restreint, puis redémarrer l'application.
Un snapshot ZFS local facilite un retour arrière, mais ne remplace pas une copie
sur un autre support. Ne jamais envoyer cette sauvegarde dans le dépôt Git.

### Restaurer sur un autre NAS

Préparer le dataset, puis restaurer **ensemble** les données et les secrets avant
le premier démarrage. Ne pas régénérer la clé de chiffrement : sans celle d'origine,
les jetons de la base ne sont plus lisibles. Adapter le propriétaire des fichiers
au nouvel UID/GID et vérifier que données et secrets restent privés.

Reprendre les sources au commit sauvegardé, reconstruire l'image, adapter les chemins
du YAML et créer la Custom App. Refaire les tests de santé, d'authentification et
HTTPS. Si le domaine change, adapter aussi l'application Tesla Developer et ses
URI de retour. Une réauthentification Tesla peut être nécessaire.

### Mettre à jour

Sauvegarder l'instance et relever le commit et le YAML actuels. Préparer les nouvelles
sources dans un répertoire distinct : ne pas remplacer `secrets/` ou `data/`.
Construire l'image avec un nouveau tag explicite, modifier seulement sa référence
dans Apps, puis vérifier le redémarrage et la santé. Conserver les anciens fichiers
et l'ancienne image jusqu'à validation. En cas de migration de données, suivre les
consignes de la version avant tout retour arrière.

Avec `pull_policy: never`, le bouton de mise à jour des images ne télécharge pas une
nouvelle version du projet : il faut la construire sur le NAS. Pour revenir à une
version antérieure compatible, remettre sa référence d'image et son YAML sauvegardé.
