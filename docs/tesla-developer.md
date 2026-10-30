# Connecter TeslaTempo à son compte Tesla

Tutoriel pour une instance personnelle auto-hébergée, vérifié avec la documentation
publique Tesla le **30 octobre 2026**. Les intitulés du portail peuvent évoluer.
L'intégration sur véhicule réel reste à valider : le relais V1
implémente OAuth et les lectures, testés avec des réponses simulées.

L'installation Docker est décrite dans le [manuel TrueNAS](installation-truenas.md).
Ce document explique les démarches à effectuer soi-même auprès de Tesla.
Chaque utilisateur dispose de sa propre application Developer et de ses propres secrets.

## 1. Préparer le compte et le domaine

Se connecter au [portail Tesla Developer](https://developer.tesla.com/) avec un
compte dont l'adresse e-mail est vérifiée et l'authentification multifacteur activée.
Pour autoriser ensuite le véhicule, utiliser le compte ayant les droits nécessaires
sur celui-ci.

Préparer une origine HTTPS dédiée au relais, par exemple `https://relay.example.com`.
Dans tout ce tutoriel, remplacer cet exemple par le domaine réel. Il faut un
certificat reconnu et un accès depuis Internet ; le certificat de l'administration
TrueNAS ou un simple tunnel SSH ne suffit pas. Un domaine dynamique reste soumis
à l'acceptation du portail Tesla.

Les prérequis et l'inscription sont décrits dans le [parcours officiel Fleet API](https://developer.tesla.com/docs/fleet-api/getting-started/what-is-fleet-api).

## 2. Créer l'application dans Tesla Developer

Depuis le portail, suivre **Get Started / Create Application** et demander l'accès
à **Fleet API**. Renseigner un nom propre à son instance, sa description et son usage.
Un nom déjà utilisé peut faire échouer la demande.

Le parcours officiel demande des informations légales sur l'entité qui porte
l'application. Renseigner les informations réelles demandées. Si le formulaire ne
permet pas de décrire son usage personnel, demander une clarification au support
Tesla ; ce tutoriel ne garantit pas l'acceptation d'un particulier et ne propose
pas de renseignements d'entreprise fictifs.

Exemple de description à adapter :

> Application Android personnelle auto-hébergée pour consulter l'état de charge
> de mon véhicule et simuler des recharges selon les tarifs électriques Tempo.
> La version actuelle accède au véhicule en lecture seule, sans commande distante.

Accepter les conditions après lecture, soumettre la demande et suivre son état
jusqu'à l'accès aux identifiants de l'application. Les délais et l'approbation
relèvent de Tesla. [Procédure officielle](https://developer.tesla.com/docs/fleet-api/getting-started/what-is-fleet-api).

## 3. Renseigner les URL et les autorisations

Utiliser les valeurs correspondant à l'origine publique réelle du relais :

| Champ / réglage | Valeur pour l'exemple |
| --- | --- |
| Allowed Origin URL | `https://relay.example.com` |
| Allowed Redirect URI / Callback URL | `https://relay.example.com/oauth/callback` |
| Domaine enregistré via l'API | `relay.example.com` |
| `PUBLIC_BASE_URL` du relais | `https://relay.example.com` |
| `TESLA_REGION` pour la France | `eu` |

Le relais emploie deux flux OAuth : `client_credentials` pour enregistrer
l'application auprès de Tesla et `authorization_code` pour le consentement du
propriétaire. Si le formulaire demande le type d'autorisation, choisir celui
permettant ces deux flux. Ne pas choisir un accès limité aux seules machines.

L'URI de retour doit correspondre exactement à celle envoyée par le relais,
sans barre finale ajoutée à `/oauth/callback`. Le dépôt GitHub n'est ni cette URI
ni l'origine de l'API. L'URL Android n'est pas le retour OAuth non plus.

Pour la V1, autoriser **Vehicle Information**, correspondant à
`vehicle_device_data`. Le code demande exactement :

```text
openid offline_access vehicle_device_data
```

`openid` et `offline_access` interviennent dans OAuth, notamment pour obtenir le
jeton de renouvellement ; ils ne sont pas forcément présentés comme des cases
métier dans le portail. Ne pas activer pour la V1 les droits de commande, de
localisation ou de gestion de la recharge. Ces droits seront réexaminés lors de
l'implémentation des commandes.

Sources : [OAuth utilisateur](https://developer.tesla.com/docs/fleet-api/authentication/third-party-tokens),
[authentification et scopes](https://developer.tesla.com/docs/fleet-api/authentication/overview),
[jetons partenaire](https://developer.tesla.com/docs/fleet-api/authentication/partner-tokens),
[régions](https://developer.tesla.com/docs/fleet-api/getting-started/regions-countries).

## 4. Configurer la facturation

Dans la rubrique de facturation et d'utilisation de l'application Developer :

1. Consulter les tarifs Fleet API actuels et les conditions du compte.
2. Configurer le moyen de paiement demandé par Tesla.
3. Définir explicitement un plafond adapté aux essais, puis surveiller l'utilisation.

Fleet API est facturée à l'usage. Tesla indique que l'absence de moyen de paiement
ou le dépassement du plafond peut désactiver l'accès. La documentation annonce
actuellement une remise mensuelle de **10 USD** pour les développeurs individuels
et petites applications : vérifier son application à son compte, sans en déduire
une gratuité garantie. Les règles et montants peuvent évoluer.

Le cache de 30 secondes du relais réduit certaines lectures répétées ; il ne garantit
pas un budget maximal. Les contrôles locaux `/healthz` ne contactent pas Tesla.
Ne pas lancer une boucle de lecture permanente pour vérifier la connexion.

Source : [facturation et limites officielles](https://developer.tesla.com/docs/fleet-api/billing-and-limits).

## 5. Publier la clé publique

L'initialisation du relais a déjà généré la paire de clés :

| Fichier dans `secrets/` | Usage |
| --- | --- |
| `tesla_public_key.pem` | Clé publique diffusée au chemin attendu par Tesla |
| `tesla_private_key.pem` | Clé privée à conserver ; non montée dans le relais V1 |

Ne pas créer une nouvelle paire à chaque configuration ou changement de poste.
Le reverse proxy doit rendre disponible, sans authentification :

```text
https://relay.example.com/.well-known/appspecific/com.tesla.3p.public-key.pem
```

Depuis un réseau extérieur, par exemple un téléphone en données mobiles, vérifier
le certificat et ce chemin. Il doit afficher la clé publique PEM, sans page de
connexion ni interface TrueNAS. Vérifier aussi `/healthz` et le refus **401** sur
`/v1/status` sans jeton.

Le fichier doit rester hébergé après l'inscription. Le relais fournit déjà cette
route ; il reste à la rendre accessible par HTTPS. La clé privée ne doit jamais
être publiée. [Exigences d'enregistrement Tesla](https://developer.tesla.com/docs/fleet-api/endpoints/partner-endpoints).

## 6. Configurer les identifiants sur le NAS

Récupérer **Client ID** et **Client Secret** dans les identifiants de l'application
Developer. Ces deux valeurs n'ont rien à voir avec le mot de passe du compte Tesla,
la clé SSH ou le jeton personnel du relais.

Sur le NAS, saisir le Client Secret dans `secrets/tesla_client_secret` avec un éditeur
local, en conservant les permissions privées et le propriétaire des autres secrets.
Ne pas le mettre dans le YAML, dans l'APK ou dans Git.

Dans **Apps → teslatempo → Edit**, adapter l'environnement du YAML :

```yaml
RELAY_MODE: tesla
PUBLIC_BASE_URL: https://relay.example.com
TESLA_REGION: eu
TESLA_CLIENT_ID: IDENTIFIANT_DE_VOTRE_APPLICATION
```

Conserver les chemins de fichiers secrets. Enregistrer la configuration et attendre
le redémarrage sain de l'application. Pour la Custom App TrueNAS, modifier `.env`
ne suffit pas : c'est le YAML enregistré dans Apps qui fait autorité.

## 7. Enregistrer l'application dans la région Tesla

Sur le NAS, depuis le répertoire d'instance contenant `scripts/` et `secrets/` :

```bash
python3 scripts/relayctl.py register --url https://relay.example.com
```

Le relais obtient un jeton partenaire et appelle l'enregistrement du domaine dans
la région configurée. Le résultat attendu contient `registered: true` et
`simulated: false`. En mode `mock`, la commande n'enregistre rien.

Cette démarche est distincte de la création dans le portail et du consentement
utilisateur. Ne pas la répéter à chaque démarrage. Pour une utilisation dans
plusieurs régions, Tesla demande l'enregistrement dans chacune d'elles.
[Enregistrement partenaire](https://developer.tesla.com/docs/fleet-api/endpoints/partner-endpoints).

Si le NAS ne peut pas atteindre son propre domaine public à cause du réseau de
la box, utiliser pour cette commande l'accès local
`--url http://127.0.0.1:18080`. Cela ne change pas `PUBLIC_BASE_URL` : Tesla doit
toujours pouvoir accéder à la clé publique par Internet.

## 8. Donner son consentement sur le site Tesla

Sur le NAS :

```bash
python3 scripts/relayctl.py connect --url https://relay.example.com
```

Ouvrir soi-même le `launch_url` renvoyé dans les **deux minutes**. Ce lien temporaire
est privé. Le navigateur visite d'abord le relais pour établir un cookie, puis
redirige vers Tesla. S'authentifier sur le site Tesla, vérifier le nom de
l'application et les autorisations, puis donner son consentement.

Terminer dans le même navigateur : le retour arrive sur `/oauth/callback`.
Ne pas recopier le code OAuth, changer de navigateur ou partager l'URL de retour.
La transaction du relais expire après dix minutes et est perdue lors d'un redémarrage.
En cas d'expiration, relancer `connect` pour obtenir un nouveau lien.

Le relais échange le code et conserve les jetons chiffrés dans sa base. Le mot de
passe Tesla n'est pas fourni au relais. La séquence OAuth est documentée par
[Tesla](https://developer.tesla.com/docs/fleet-api/authentication/third-party-tokens) ;
les durées ci-dessus sont celles du code TeslaTempo V1.

## 9. Vérifier les lectures

Toujours dans le répertoire d'instance :

```bash
python3 scripts/relayctl.py status --url https://relay.example.com
python3 scripts/relayctl.py vehicles --url https://relay.example.com
python3 scripts/relayctl.py charge-state --url https://relay.example.com --vehicle IDENTIFIANT_NUMERIQUE
```

Remplacer le dernier argument par l'identifiant renvoyé par `vehicles`, pas un VIN.
Les données doivent être réelles, avec `simulated: false`. Un état connecté indique
la présence des jetons ; seule une lecture réussie confirme l'accès au compte à cet instant.
Un véhicule endormi peut être indisponible : cette V1 ne le réveille pas automatiquement.

Configurer ensuite Android avec la même origine HTTPS et le **jeton personnel du
relais** (`secrets/relay_api_token`), jamais le Client Secret Tesla.

## 10. Clé virtuelle : étape future pour les commandes

La publication de la clé sur le domaine et son appairage au véhicule sont deux
opérations différentes. L'appairage sert notamment aux commandes signées et à
Fleet Telemetry ; ces fonctions ne sont pas intégrées à notre V1 de lecture.

Lorsque cette évolution sera disponible, le propriétaire pourra ouvrir sur son
téléphone, avec l'application officielle Tesla installée :

```text
https://www.tesla.com/_ak/relay.example.com
```

Le suffixe est le domaine seul, sans `https://` supplémentaire ni chemin. Vérifier
le domaine affiché, sélectionner le véhicule et suivre les instructions officielles
d'appairage. Ajouter la clé n'active pas à lui seul les commandes dans TeslaTempo :
il faudra aussi le logiciel de signature et les autorisations OAuth adaptées.

Sources : [clés virtuelles](https://developer.tesla.com/docs/fleet-api/virtual-keys/overview),
[guide d'appairage](https://developer.tesla.com/docs/fleet-api/virtual-keys/developer-guide).

## Dépannage et révocation

| Symptôme | Vérification |
| --- | --- |
| Nom d'application refusé | Choisir un nom propre à l'instance ; vérifier les indications du portail |
| Domaine ou clé rejetés | DNS, certificat, accès public au PEM et cohérence des domaines déclarés |
| Retour OAuth refusé | URI exacte, cookie du même navigateur, délai et absence de redémarrage |
| HTTP 401 du relais | Jeton personnel du relais correct ; puis consentement et identifiants Tesla |
| Véhicule absent | Compte utilisé, droits accordés, sélection du véhicule et région |
| Lecture indisponible | Véhicule endormi, réseau ou service Tesla ; éviter les tentatives en boucle |
| Accès interrompu malgré OAuth | Vérifier aussi le paiement et le plafond dans Tesla Developer |
| HTTP 429 | Attendre avant de réessayer ; respecter la limitation annoncée |

Le relais masque les détails sensibles des erreurs amont ; son code HTTP ne reproduit
pas toujours celui de Tesla. Ne pas joindre de secrets ou de liens OAuth aux diagnostics.

Pour retirer l'autorisation côté Tesla, ouvrir les paramètres du compte, puis
**Security → Third Party Apps → Manage**, et révoquer l'accès à l'application.
Le [guide officiel de gestion des accès](https://www.tesla.com/support/access-third-party-apps)
décrit aussi le parcours depuis l'application mobile.

Pour effacer également les jetons de l'instance locale :

```bash
python3 scripts/relayctl.py disconnect --url https://relay.example.com
```

Cette commande seule ne révoque pas le consentement chez Tesla. Une clé virtuelle
ajoutée au véhicule peut aussi être retirée depuis l'écran de gestion des clés.
Conserver les sauvegardes protégées : elles peuvent encore contenir d'anciens jetons chiffrés.
