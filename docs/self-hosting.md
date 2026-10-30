# Déploiement auto-hébergé de Tesla Tempo AI

Le Dockerfile et Compose du relais V1 en lecture seule sont disponibles. Le [guide du relais](relay-v1.md) décrit l'installation et les vérifications à réaliser. Aucune image n'est publiée dans un registre.

## Principe

Chaque utilisateur est responsable de déployer et d'exploiter sa propre instance du relais sur un NAS compatible, une VM Linux ou un autre hébergement adapté. Le projet ne fournit pas de service central hébergé, de compte Tesla Developer commun ou de secrets partagés. Le NAS TrueNAS est le premier environnement envisagé ; les instructions exactes dépendront de sa version.

L'application Android permettra de saisir l'URL HTTPS de cette instance et de s'y authentifier. Le domaine personnel du développeur ne sera pas codé en dur. Cette architecture remplace la contrainte initiale d'absence de serveur permanent.

## Livrables du projet

- Le code du relais V1 et son Dockerfile dans le dépôt public.
- Une image constructible localement sans identifiants ni données personnelles ; distribution en registre à venir.
- Un exemple Docker Compose, les volumes requis et une configuration d'exemple contenant uniquement des valeurs fictives.
- Les instructions pour configurer HTTPS, enregistrer l'application Tesla, appairer la clé et vérifier l'installation.
- Une procédure de mise à jour et de sauvegarde/restauration.

Publier un Dockerfile est compatible avec cette architecture : il décrit la construction du logiciel. Aucun secret ne doit être présent dans ses instructions, ses arguments de construction, le contexte envoyé au moteur de build ou les couches de l'image. Les exclusions Git et Docker devront protéger les fichiers locaux sensibles.

## Ce que chaque utilisateur doit préparer

1. Un hébergement et un stockage persistant pour l'instance.
2. Un domaine accepté lors de l'enregistrement Tesla, sa configuration DNS et un certificat HTTPS valide. La présence d'un domaine dynamique ne garantit pas à elle seule son acceptation par Tesla.
3. Sa propre application Tesla Developer, son client ID, son secret OAuth, les scopes nécessaires et une URI de retour correspondant exactement à sa configuration.
4. Sa propre paire de clés de signature Tesla, l'enregistrement régional et l'appairage de la clé publique au véhicule.
5. Facultativement, son projet et sa clé OpenAI pour activer l'assistant.
6. La facturation et les plafonds des API utilisées.

Les secrets sont fournis à l'exécution via des fichiers protégés montés dans les conteneurs ou un gestionnaire de secrets compatible. Ils restent absents du dépôt, de l'image, de l'APK et des logs. Les jetons OAuth générés sont conservés dans un stockage persistant protégé. Aucun secret ne doit être communiqué à l'auteur du projet.

## Exposition HTTPS

La clé **publique** doit rester accessible depuis Internet, sans authentification, à :

```text
https://DOMAINE/.well-known/appspecific/com.tesla.3p.public-key.pem
```

Cette publication et l'enregistrement du domaine sont décrits dans le [guide Tesla des clés virtuelles](https://developer.tesla.com/docs/fleet-api/virtual-keys/developer-guide) et les [endpoints partenaire](https://developer.tesla.com/docs/fleet-api/endpoints/partner-endpoints).

Le relais expose également les routes nécessaires à la connexion et à l'application Android :

- Le retour OAuth valide la transaction de connexion avant d'accepter un code ; il n'est pas un accès libre aux commandes.
- Les routes de données et d'action exigent une authentification et les contrôles métier.
- Le futur proxy Tesla de signature restera interne au déploiement ; il ne sera pas exposé directement sur Internet. Il n’est pas inclus dans la V1 en lecture seule.
- La clé privée, les secrets, les volumes et l'administration du NAS ne sont jamais publiés par le serveur web.

Un reverse proxy HTTPS peut diriger ces chemins vers les services appropriés. L'utilisateur assure leur accessibilité, le renouvellement des certificats et la configuration réseau nécessaire, sans exposer l'interface d'administration TrueNAS.

## Exploitation et limites

L'utilisateur assure les mises à jour, sauvegardes protégées, renouvellements de certificat, rotation/révocation des secrets et suivi des coûts. Il doit préserver les volumes lors d'une mise à jour de l'image.

Si le relais ou la connexion Internet est indisponible, aucune nouvelle commande ne peut être garantie. L'exécution d'un programme natif déjà confirmé doit rester indépendante du NAS ; cette propriété sera testée sur le véhicule. L'auto-hébergement ne garantit ni un historique complet ni l'acceptation de toutes les programmations par Tesla.

Les essais initiaux se font en lecture seule. La commande réelle et le comportement F20 restent soumis aux validations listées dans [l'audit de faisabilité](feasibility.md).
