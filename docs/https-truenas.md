# Ajouter HTTPS à la Custom App TrueNAS

Ce guide complète l'[installation du relais](installation-truenas.md). Il prépare
un reverse proxy Caddy dans la même application TrueNAS que le relais. La validation
de syntaxe ne prouve pas l'accès depuis Internet : effectuer les tests finaux.

## Préparer le domaine et les ports

Choisir le domaine qui sera utilisé dans Android et Tesla Developer. Si ce domaine
ou les ports publics 80/443 desservent déjà un service, inventorier les redirections
avant de les modifier. Un second domaine partageant la même adresse IP nécessite
un reverse proxy commun pour partager ces ports.

Le DNS A doit pointer vers l'adresse IPv4 publique de la box. Si un AAAA existe,
son accès IPv6 doit également aboutir au bon proxy. Conserver l'adresse du NAS
stable (configuration statique ou réservation DHCP). Sur une Freebox à IPv4
partagée, vérifier que les ports 80 et 443 font partie de la plage attribuée.
Sinon, examiner la possibilité d'une IPv4 full-stack avant de continuer.

## Préparer Caddy sur le NAS

Utiliser le [YAML HTTPS complet](../deploy/truenas-https-compose.example.yaml)
pour remplacer le YAML de **l'application teslatempo existante**, sans créer une
deuxième instance. Sauvegarder le YAML courant avant la modification.

Adapter :

- `192.0.2.25` : adresse privée réelle du NAS (cette adresse est un exemple documentaire) ;
- `/mnt/POOL/teslatempo` : dataset réel ;
- `user` du relais : UID/GID existants, sans changer le propriétaire des données ;
- `PUBLIC_BASE_URL` : origine HTTPS réelle ;
- le domaine dans le [Caddyfile](../deploy/Caddyfile.truenas.example).

Copier ce Caddyfile adapté dans `source/Caddyfile` sur le NAS et créer les dossiers
`caddy-data` et `caddy-config` à la racine du dataset. Caddy s'exécute avec le compte
root du conteneur et la seule capacité `NET_BIND_SERVICE` ; ces deux dossiers
doivent appartenir à root et être privés (mode 700). Le Caddyfile, sans secret,
doit être lisible dans le conteneur (mode 644, ou propriétaire root). Ils conservent notamment les certificats
et leurs clés privées. Les inclure dans les sauvegardes chiffrées.

Le relais reste non-root et son port 18080 reste limité à la boucle locale du NAS.
Caddy le joint par `relay:8080` sur le réseau Docker de l'application. Les ports
internes 80/443 du conteneur Caddy sont publiés sur **18081/18443 du NAS** : ils
n'entrent pas en conflit avec l'administration TrueNAS.

Conserver `RELAY_MODE: mock` jusqu'à validation HTTPS. Le YAML n'active aucune
journalisation des URL d'accès, qui pourraient contenir des codes OAuth.
L'image Caddy est versionnée ; une installation peut aussi la verrouiller par digest.

## Redirections Freebox

Dans Freebox OS, ouvrir **Paramètres de la Freebox → Mode avancé → Gestion des ports**.
Après vérification des règles existantes, créer les deux redirections TCP :

| Port public de début et de fin | IP de destination | Port de destination |
| --- | --- | --- |
| 80 | Adresse privée du NAS | 18081 |
| 443 | Adresse privée du NAS | 18443 |

La source doit permettre les connexions Internet pour que l'autorité de certification
et Tesla puissent accéder au service. Ne pas rediriger vers les ports 80/443 de
l'administration du NAS ni vers le port HTTP 18080 du relais. Ne pas activer de DMZ.
Ne pas supprimer une règle utilisée par un autre service sans organiser sa migration.

Caddy obtient le certificat puis le renouvelle automatiquement tant que le DNS,
le stockage persistant et les chemins de validation restent opérationnels.
Voir [HTTPS automatique Caddy](https://caddyserver.com/docs/automatic-https) et
[la gestion des redirections Freebox](https://dev.freebox.fr/sdk/os/nat/).

## Vérifier avant de passer à Tesla

Dans les journaux Caddy, vérifier l'obtention du certificat. Une erreur de validation
invite à corriger le DNS ou les ports ; éviter de multiplier les demandes de certificat.

Depuis un réseau extérieur (par exemple téléphone en données mobiles), vérifier :

1. `https://DOMAINE/healthz` répond `ok` sans avertissement de certificat.
2. `https://DOMAINE/.well-known/appspecific/com.tesla.3p.public-key.pem`
   renvoie la clé publique PEM.
3. `https://DOMAINE/v1/status` sans jeton renvoie HTTP 401.
4. `http://DOMAINE/healthz` redirige vers HTTPS sur le port public 443.

La racine `/` peut répondre 404 : le relais ne propose pas d'interface web.
Tester ensuite une lecture authentifiée avec `relayctl.py` et l'application Android.
Ne pas désactiver la validation des certificats pour considérer HTTPS comme fonctionnel.

Depuis le réseau local, certains routeurs nécessitent une configuration supplémentaire
pour accéder au domaine public (bouclage NAT ou DNS local). Le test depuis Internet
reste indispensable même si l'accès local fonctionne.

## Autoriser un outil d'installation dans Freebox OS

Si un outil local a été associé à la Freebox par validation sur son écran, il peut
lire la configuration sans avoir le droit de la modifier. Pour lui permettre de
créer les redirections : **Paramètres de la Freebox → Mode avancé → Gestion des accès
→ Applications**, ouvrir l'application concernée, cocher **Modification des réglages
de la Freebox**, puis appliquer. Ce droit peut être retiré après l'installation ;
le relais et le renouvellement du certificat n'en dépendent pas.

Lors du passage en IPv4 full-stack, attendre le délai annoncé par Free avant de
redémarrer le Server. Vérifier ensuite l'adresse IPv4, la plage de ports attribuée
et la mise à jour du domaine dynamique. Conserver les règles des services déjà
utilisés, notamment le VPN. Le changement d'IP peut interrompre leur accès distant
jusqu'à mise à jour du domaine et reconnexion des clients.

## Retour arrière

Restaurer le YAML précédent de la Custom App et retirer seulement les redirections
créées pour TeslaTempo si elles ne servent plus. Conserver les données du relais et
les certificats. L'interface TrueNAS reste accessible à son adresse locale : ce
parcours ne modifie ni son certificat ni ses ports d'administration.
