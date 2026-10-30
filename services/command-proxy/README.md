# Proxy de signature Tesla — préparation

Image construite à partir du [SDK officiel Tesla](https://github.com/teslamotors/vehicle-command)
à la révision `d6239bf377b8a5859e8f5c9d64409df2a057934a`, avec vérification du
SHA-256 de l'archive. La licence Apache 2.0 du SDK est incluse dans l'image.
Ce composant n'ajoute aucune route de commande au relais actuel et n'est pas
activé sur une installation existante par une simple mise à jour du relais.

Depuis la racine :

```bash
docker build -t teslatempo-command-proxy:0.1.0 services/command-proxy
.venv/bin/python scripts/init-command-proxy.py --directory secrets
```

Le script crée une clé TLS dédiée et un certificat local d'un an, valables pour
`localhost` et `127.0.0.1`. Il refuse de remplacer des fichiers existants. Ne jamais
réutiliser la clé privée Tesla comme clé TLS. Les permissions et propriétaires
doivent permettre la lecture par l'UID du conteneur, sans ouvrir les secrets aux
autres utilisateurs.

Le [fragment Compose](../../deploy/command-proxy.compose.example.yaml) est facultatif :

```bash
docker compose -f compose.yaml -f deploy/command-proxy.compose.example.yaml \
  --profile command-proxy up -d --build
```

Les chemins du fragment sont résolus depuis le Compose racine. La clé de signature
existante `secrets/tesla_private_key.pem` doit être présente et correspondre à la
clé publique enregistrée. Le proxy partage le réseau du relais et écoute seulement
sur sa boucle locale, sans port publié. Ne pas créer de route Caddy vers lui.
Aucun log détaillé n'est activé.

Le client du relais valide le certificat local et limite les commandes ; son
branchement futur nécessitera un jeton OAuth Tesla avec les droits adaptés.
Ne pas activer de client HTTP générique, ni désactiver la vérification TLS.

## Ce qui reste à valider

Le démarrage TLS peut être testé avec des clés fictives et sans réseau externe.
Cela ne prouve pas que la signature soit acceptée par un véhicule. Il faut encore
le branchement du lecteur Fleet réel, une clé virtuelle réellement appairée,
et la validation des lectures préalables et des relectures sur le véhicule.
La rotation du certificat local doit être planifiée avant son expiration ; les
anciens fichiers ne sont pas remplacés automatiquement.

Test Docker reproductible, après construction des deux images :

```bash
.venv/bin/python scripts/check-command-proxy.py
```

Le test utilise l'image cliente `teslatempo-relay:0.4.0`, crée des clés temporaires,
vérifie TLS et le refus d'une requête sans authentification, puis supprime ses
conteneurs. Le proxy testé n'a aucun réseau externe.

Le [transport TLS à commandes limitées](../../docs/command-orchestration.md) est
implémenté dans le relais, sans route de commande activée. Le smoke test exerce
aussi ce client avec un jeton fictif sur un conteneur sans réseau extérieur.
