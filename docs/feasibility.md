# Tesla Tempo AI — audit de faisabilité

Date : 29 octobre 2026, Europe/Paris. Référence : cahier des charges v1.4. Audit initial fondé sur la v1.3 ; décision d’auto-hébergement intégrée le 29 octobre 2026.

Audit initial documentaire : aucun compte connecté, aucun appel authentifié Tesla et aucune commande véhicule. Depuis, le relais V1 est implémenté et testé localement avec des secrets de démonstration générés ; OAuth et les lectures sont testés avec un transport simulé. Voir [le guide V1](relay-v1.md). Les liens ci-dessous sont les sources officielles consultées ; leur contenu et les conditions commerciales peuvent évoluer.

## 1. Conclusion et définition des statuts

**Le développement sur simulateurs peut commencer. L'intégration réelle nécessite encore l'ouverture des accès et des essais sur la Model Y.**

- **VALIDÉ** : capacité ou contrainte établie par une source officielle, sans validation sur le compte ou le véhicule de l'utilisateur.
- **À TESTER** : hypothèse d'architecture ou capacité nécessitant un essai technique ou une vérification dans le portail.
- **BLOQUANT** : empêche la fonctionnalité réelle dans les conditions actuelles ; ne bloque pas les mocks.

| Point | Statut | Conclusion |
| --- | --- | --- |
| OAuth Tesla entièrement dans un APK, sans intermédiaire de confiance | BLOQUANT | L'échange initial documenté exige un `client_secret`. Aucun flux public mobile sans secret n'a été établi par cet audit. |
| Android avec relais HTTPS auto-hébergé | À TESTER | Architecture du projet ; déploiement TrueNAS, SDK Tesla et gestion des secrets à valider. |
| Compte Tesla Developer et enregistrement de l'application | BLOQUANT | Non configurés d'après le cahier des charges. |
| Lecture batterie/charge | VALIDÉ / À TESTER | Endpoints documentés ; permissions et réponses du véhicule à vérifier. |
| Commandes signées | VALIDÉ / À TESTER | Protocole officiel disponible ; appairage et exécution non réalisés. |
| Début de charge sans heure de fin, jusqu'à la cible | VALIDÉ / À TESTER | Décrit dans le manuel Model Y ; correspondance API et comportement réel à confirmer. |
| Optimisation arbitraire multi-créneaux | À TESTER | Ne pas assimiler un planning mathématique à un planning exécutable par Tesla. |
| Historique et factures Supercharger | À TESTER | Endpoints candidats disponibles ; accès et profondeur historique inconnus pour ce compte. |
| Endpoint `charging_sessions` pour particulier | BLOQUANT | Réservé aux comptes professionnels propriétaires d'une flotte. |
| Assistant Responses API avec outils contrôlés | VALIDÉ / À TESTER | Interface documentée ; compte, modèle accessible et budget à configurer. |
| Clé OpenAI statique dans l'APK | BLOQUANT | Incompatible avec les consignes de protection des clés OpenAI. |
| Historique domestique exhaustif, téléphone indisponible | À TESTER | La charge autonome n'assure pas la collecte autonome des mesures. Prévoir des sessions partielles. |

## 2. Tesla : accès, OAuth et autorisations

### Compte et domaine

Tesla demande un compte avec email vérifié et MFA, une demande d'application, une paire de clés P-256 (`prime256v1`), la publication de sa partie publique et un enregistrement régional. Le formulaire mentionne des informations professionnelles : l'acceptation du cas personnel reste à vérifier honnêtement dans le portail. L'usage hobbyiste est néanmoins mentionné dans la documentation d'authentification.

Source : [onboarding Tesla](https://developer.tesla.com/docs/fleet-api/getting-started/what-is-fleet-api), [types de jetons et scopes](https://developer.tesla.com/docs/fleet-api/authentication/overview).

Adresse publique requise : `https://DOMAINE/.well-known/appspecific/com.tesla.3p.public-key.pem`. Seule la clé publique y sera publiée. Le domaine effectif et l'URI de retour OAuth restent à choisir avant de soumettre la configuration définitive.

Pour un compte européen, base candidate : `https://fleet-api.prd.eu.vn.cloud.tesla.com`. Confirmer la région du compte ; ne pas recopier l'audience américaine des exemples. L'enregistrement passe par `POST /api/1/partner_accounts` avec un jeton partenaire. La France figure parmi les pays prenant en charge les réglages de paiement.

Sources : [régions](https://developer.tesla.com/docs/fleet-api/getting-started/regions-countries), [endpoints partenaire](https://developer.tesla.com/docs/fleet-api/endpoints/partner-endpoints).

### OAuth

Le flux officiel utilise `https://auth.tesla.com/oauth2/v3/authorize`, puis `POST https://fleet-auth.prd.vn.cloud.tesla.com/oauth2/v3/token`. L'échange du code exige le secret client. Le renouvellement documenté utilise le refresh token et le client ID, avec rotation des refresh tokens ; ceux-ci expirent après trois mois et doivent être sauvegardés après chaque échange. La présence de PKCE comme substitut au secret n'est pas établie.

**Déduction d'architecture :** confier l'échange initial à un composant de confiance. Recommandation : conserver aussi les jetons dans ce relais, avec stockage chiffré durable et renouvellement sérialisé. Android reçoit sa propre session applicative. Prévoir navigateur système, validation de `state`, retour HTTPS, liaison sûre à la session Android et absence de jetons dans les URL de retour.

Source : [Third-Party Tokens](https://developer.tesla.com/docs/fleet-api/authentication/third-party-tokens).

### Scopes minimaux proposés

| Scope | Usage |
| --- | --- |
| `openid`, `offline_access` | Connexion et renouvellement. |
| `vehicle_device_data` | Batterie, état et capacités du véhicule. |
| `vehicle_charging_cmds` | Historique et gestion de recharge. Ce scope inclut des écritures : le premier test reste limité aux lectures par notre code. |
| `vehicle_location` | Vérification consentie du domicile avant pilotage ; demander lorsque nécessaire. |
| `vehicle_cmds` | Seulement si un réveil explicite devient nécessaire ; autorisation large à éviter initialement. |

Ne pas demander `user_data`, les scopes énergie ou entreprise sans besoin. Un conducteur invité peut ne pas avoir accès à la localisation même avec le scope. Si le domicile ne peut pas être établi avec une donnée fraîche, refuser le pilotage automatique.

Source : [scopes Tesla](https://developer.tesla.com/docs/fleet-api/authentication/overview).

## 3. Signature et programmation autonome

Le SDK officiel `vehicle-command` est écrit en Go et fournit un proxy de signature. Il requiert à la fois l'autorisation OAuth et une clé reconnue par le véhicule. Le dépôt décrit un fonctionnement macOS/Linux : aucun SDK Kotlin Android prêt à intégrer n'est établi ici. Une intégration Go/Android ou une implémentation du protocole nécessiterait un prototype spécifique.

**Choix retenu :** exécuter le SDK dans le déploiement conteneurisé auto-hébergé de chaque utilisateur. Tester redémarrage, durée maximale des requêtes, reprise des sessions et concurrence. Le proxy officiel ne doit pas devenir une API publique générique : placer une couche d'authentification et une liste fermée de commandes de recharge devant lui.

Source : [SDK officiel Tesla](https://github.com/teslamotors/vehicle-command/blob/main/README.md).

La paire P-256 publiée lors de l'enregistrement sert au parcours officiel de clé virtuelle ; ce n'est pas le secret OAuth. La clé privée signe, la clé publique est appairée au véhicule. Le parcours utilisateur utilise `https://tesla.com/_ak/DOMAINE`, après autorisation de l'application. L'appairage nécessite une action de l'utilisateur et la clé publique doit rester accessible. Aucun appairage n'est effectué pendant cet audit.

Source : [guide des clés virtuelles](https://developer.tesla.com/docs/fleet-api/virtual-keys/developer-guide).

### Endpoints à intégrer

| Méthode et chemin | Utilisation / validation restante |
| --- | --- |
| `GET /api/1/vehicles` | Choix du véhicule, accès du compte. |
| `POST /api/1/vehicles/fleet_status` | Consultation des capacités, firmware et besoin du protocole signé ; lecture malgré la méthode POST. |
| `GET /api/1/vehicles/{vin}/vehicle_data` | Batterie et charge ; demander aussi `charge_schedule_data` pour les programmations. |
| `POST /api/1/vehicles/{vin}/command/set_charge_limit` | Fixer la cible de 80 %, puis vérifier l'état. |
| `POST /api/1/vehicles/{vin}/command/set_charging_amps` | Limite initiale de 10 A, jamais supérieure dans le prototype. |
| `POST /api/1/vehicles/{vin}/command/add_charge_schedule` | Ajouter un programme natif représentable. |
| `POST /api/1/vehicles/{vin}/command/remove_charge_schedule` | Retirer uniquement un programme appartenant à l'application. |
| `POST /api/1/vehicles/{vin}/command/charge_start` | Secours continu, uniquement après validation. |
| `POST /api/1/vehicles/{vin}/command/charge_stop` | Annulation explicite ; ne pas l'utiliser automatiquement à 6 h. |

Sources : [Vehicle Endpoints](https://developer.tesla.com/docs/fleet-api/endpoints/vehicle-endpoints), [Vehicle Commands](https://developer.tesla.com/docs/fleet-api/endpoints/vehicle-commands).

Les nouveaux endpoints de programmation sont annoncés pour les firmwares 2024.26 et ultérieurs. Les anciens `set_scheduled_charging` et `set_scheduled_departure` ne doivent pas constituer le socle du projet. Source : [annonces Tesla](https://developer.tesla.com/docs/fleet-api/announcements).

### Conséquences pour F20

Le manuel Model Y décrit un début sans fin qui poursuit jusqu'à la limite, des programmes liés au lieu, une exécution ponctuelle ou hebdomadaire et une fusion de certains créneaux qui se chevauchent. Il décrit aussi des démarrages immédiats selon l'heure du branchement. Il ne faut donc pas modéliser chaque créneau comme un interrupteur indépendant. Le manuel consulté est en anglais américain : vérifier la version du véhicule européen.

Source : [programmation Model Y](https://www.tesla.com/ownersmanual/modely/en_us/GUID-76995CEC-6402-4BFF-99FA-CEFA36E64A19.html).

Le protocole expose une liste de programmes et un champ de capacité maximale ; ne pas fixer arbitrairement le nombre de créneaux supportés. Source : [schéma officiel Vehicle](https://raw.githubusercontent.com/teslamotors/vehicle-command/main/pkg/protocol/protobuf/vehicle.proto).

**Proposition à tester :** début natif au créneau économique sélectionné, aucune fin forcée à 6 h, cible 80 %, courant plafonné à 10 A. Si non représentable : charge continue acceptée explicitement, avec surcoût estimé. La traduction exacte des paramètres REST/protocole reste à valider lors du prototype ; aucun payload de production n'est déclaré prêt.

Vérifier avant application les programmes manuels, le branchement, le lieu et la fraîcheur du SoC. Une suite de commandes n'est pas une transaction atomique : journaliser les réussites partielles. Après timeout, relire avant de réémettre. `APPLIED_CONFIRMED` exige une confirmation, idéalement une relecture ; sinon conserver `UNKNOWN_OUTCOME`. Source : [bonnes pratiques Tesla](https://developer.tesla.com/docs/fleet-api/getting-started/best-practices).

## 4. Historique, coûts et limites Tesla

`GET /api/1/dx/charging/history` fournit un historique paginé ; `GET /api/1/dx/charging/invoice/{id}` fournit une facture PDF. En revanche, `GET /api/1/dx/charging/sessions` est explicitement réservé aux propriétaires professionnels de flotte. Tester les deux premiers sur le compte personnel : pagination, champs kWh/coût/devise, anciens événements et factures. Prévoir un import manuel si l'accès ou les champs sont insuffisants.

Source : [Charging Endpoints](https://developer.tesla.com/docs/fleet-api/endpoints/charging-endpoints).

**Limite de conception :** les sources consultées ne démontrent pas un historique domestique exhaustif récupérable après une longue absence du téléphone. Sans collecte continue, certaines sessions resteront `PARTIAL` ou `ESTIMATED`. Fleet Telemetry nécessite un récepteur serveur et sort du socle minimal retenu ici ; sa mise en place serait une décision distincte. La continuité de recharge n'implique pas la continuité des statistiques.

### Budget indicatif

Tarifs publics USD observés : données 0,002 $/requête, commandes 0,001 $/requête, réveils 0,02 $/requête, signaux 1 $/150 000. Illustration calculée, hors taxes, remise et hébergement : 600 lectures + 150 commandes + 30 réveils = **1,95 $/mois**. Un polling de données toutes les cinq minutes pendant 30 jours représenterait 8 640 lectures, soit **17,28 $**, avant les autres appels. La catégorie de chaque endpoint doit être vérifiée avant chiffrage définitif.

Source : [tarification Tesla en USD](https://developer.tesla.com/en_US).

Tesla annonce une remise mensuelle de 10 $, à vérifier dans le compte et la devise de facturation. Un moyen de paiement et un plafond doivent être configurés ; ne pas promettre la gratuité. Les plafonds publiés sont 60 lectures temps réel, 30 commandes et 3 réveils par minute, par appareil et compte, partagés entre applications. Respecter les réponses de limitation et borner les tentatives. Ce sont des plafonds, pas des cadences recommandées.

Source : [Billing and Limits](https://developer.tesla.com/docs/fleet-api/billing-and-limits). Cette page a été récupérée directement en HTTPS après des échecs du navigateur documentaire ; elle contient aussi une ancienne mention de période de transition, non utilisée pour cet audit.

## 5. OpenAI

**VALIDÉ :** Responses API et function calling répondent au besoin d'assistant. Une proposition d'outil est exécutée par notre application. Utiliser des schémas stricts, puis les contrôles métier Kotlin : un schéma valide n'est pas une autorisation d'action. Les outils de lecture/simulation peuvent fonctionner directement ; application et annulation nécessitent une confirmation liée à la version exacte du plan. Limiter le nombre d'appels d'outils par tour.

Source : [function calling](https://developers.openai.com/api/docs/guides/function-calling).

**BLOQUANT pour un APK contenant une clé partagée :** OpenAI demande de conserver les clés côté serveur. Le relais proposé conserve sa clé dans un gestionnaire de secrets, authentifie le téléphone, limite requêtes et tokens, et n'accepte aucun choix arbitraire de fournisseur ou d'URL. Un mode BYOK direct expose toujours un secret sur le téléphone ; il n'est pas retenu par défaut. L'utilisation de jetons de workload identity ne démontre pas à elle seule un flux de connexion mobile grand public adapté.

Source : [authentification OpenAI](https://developers.openai.com/api/reference/overview).

**Choix actuel :** privilégier l'usage sans clé API. Android permet un partage
volontaire d'agrégats vers ChatGPT. Pour l'intégration, le parcours officiel
Sign in with ChatGPT permet aux comptes Plus et Pro éligibles d'utiliser leur
abonnement avec OAuth. L'éligibilité, les scopes et les quotas restent à tester ;
ce parcours est implémenté et testé avec des réponses simulées, à valider avec chaque compte. Aucun projet API payant n'est requis pour
le partage manuel. Voir [le guide ChatGPT](chatgpt.md) et le
[quickstart officiel OpenAI](https://developers.openai.com/siwc/quickstart).

Proposition : conversations locales, requêtes `store: false`, données agrégées, sans VIN, secrets ni coordonnées précises. Ce paramètre ne promet pas l'absence de toute rétention : la politique distingue état applicatif, surveillance des abus et exceptions. Aucun contrat Zero Data Retention n'est supposé.

Source : [contrôles de données OpenAI](https://developers.openai.com/api/docs/guides/your-data).

## 6. Architecture retenue : relais auto-hébergé

Chaque utilisateur déploie son propre relais avec ses comptes et secrets. Aucun hébergement mutualisé n’est prévu.

1. **Android** : moteur déterministe, données locales, confirmation des plans, URL HTTPS du relais configurable et authentification auprès de celui-ci.
2. **Entrée HTTPS** : publication de la clé publique Tesla et routes applicatives nécessaires, avec certificat valide.
3. **Relais et proxy Tesla interne** : OAuth, stockage protégé, signature via SDK officiel et accès OpenAI facultatif. Aucune sortie du modèle ne déclenche directement une commande.
4. **Stockage persistant** : jetons, clés, sessions et audit préservés lors des mises à jour, avec permissions et sauvegardes adaptées.
5. **Tesla** : exécution autonome du programme accepté, à confirmer sur véhicule réel. Une panne du NAS empêche les nouvelles opérations ; elle ne doit pas être compensée par une promesse de commande Android à heure exacte.

Le Dockerfile, l’image constructible et Compose de la V1 en lecture seule sont implémentés et testés localement. Le proxy de signature et la distribution d’image en registre restent à réaliser. Chaque utilisateur crée sa propre application Tesla Developer et assure domaine, HTTPS, enregistrement, appairage, facturation et maintenance. L'acceptation de son inscription et de son domaine reste à vérifier auprès de Tesla.

Voir [docs/self-hosting.md](self-hosting.md) pour le contrat de déploiement. Le recours à un relais cloud partagé ou serverless reste une évolution possible, hors du choix retenu.

## 7. Démarches utilisateur, dans l'ordre

### Maintenant — informations non secrètes

- [ ] Configurer le domaine personnel de l’utilisateur. Vérifier HTTPS, DNS et acceptation Tesla ; aucun domaine personnel ne doit devenir une valeur par défaut du projet.
- [x] Retenir un relais auto-hébergé par chaque utilisateur, avec ses propres secrets.
- [ ] Vérifier la compatibilité Docker et le réseau de l’hébergement choisi.
- [ ] Configurer le dataset, les permissions et l'entrée HTTPS pour une Custom App ou une VM Linux.
- [ ] Indiquer l'année et le firmware de la Model Y, et le rôle du compte : propriétaire ou conducteur invité. Pas de VIN demandé.
- [ ] Définir le budget mensuel maximal API + hébergement.
- [ ] Vérifier l'email et la MFA du compte Tesla, puis accéder au [portail Tesla Developer](https://developer.tesla.com/). Vérifier que la demande d'application personnelle est possible ; ne pas inventer d'identité professionnelle.

### Après choix du domaine et du relais — préparation des accès

- [ ] Soumettre l'application Tesla avec une description fidèle : « Application Android privée d'optimisation de recharge domestique EDF Tempo, consultation de batterie, historique et programmation de recharge consentie. »
- [ ] Configurer les scopes utiles ; renseigner les URL définitives préparées pour l'hébergeur retenu. Une URI de retour OAuth doit correspondre exactement à celle enregistrée.
- [ ] Consulter les prix et la remise du compte, ajouter le paiement et fixer le plafond souhaité avant les appels réels.
- [ ] Fournir uniquement le statut de validation de l'application, le domaine et les éventuels messages d'erreur expurgés. Le client ID est public mais n'est pas nécessaire dans le chat à ce stade.
- [ ] Déposer soi-même le secret Tesla dans le coffre de l'hébergement une fois celui-ci préparé. La génération de la clé de signature, la publication de sa partie publique et l'enregistrement régional seront guidés à ce moment-là ; aucune clé privée ne doit être envoyée dans le chat.
- [ ] Pour GPT, essayer le partage volontaire sans clé API ; valider ensuite le parcours Sign in with ChatGPT sur son instance. Voir [le guide](chatgpt.md).

### Plus tard — essais réels encadrés

- [ ] Se connecter par le parcours OAuth préparé et consentir aux permissions choisies.
- [ ] Réaliser d'abord les lectures : batterie, état, programmes existants, historique et factures.
- [ ] Lors de l'étape commandes, être disponible près de la voiture avec l'application Tesla et le téléphone déjà appairé pour accepter la clé virtuelle.
- [ ] Choisir un créneau de test à domicile, voiture branchée, cible et courant affichés, puis confirmer le plan de test avant toute écriture.

Ces essais ne sont pas autorisés implicitement par le présent audit. Ils seront proposés avec les réglages précis et leur restauration. Aucun mot de passe, token, secret client ou clé API n'est à transmettre ici.

## 8. Preuves nécessaires avant de déclarer l'intégration prête

| Test futur | Résultat attendu |
| --- | --- |
| OAuth, renouvellement concurrent, révocation | Jetons renouvelés sans perte ; accès révoqué traité sans boucle. |
| Lecture et quotas | SoC et programmes obtenus ; pas de réveils répétitifs. |
| Historique personnel | Accès, pagination, dédoublonnage, présence des montants et énergie vérifiés sur réponses réelles. |
| Signature | Clé reconnue et commande de recharge ciblée confirmée. |
| F20 | Limite 80 %, courant ≤10 A, continuation après 6 h et départ indicatif, téléphone éteint après confirmation du programme. |
| Programmations | Conservation des programmes manuels, chevauchements, minuit, heure locale et changement d'heure vérifiés. |
| Erreur partielle/timeout | Aucun faux succès ; relecture et reprise bornée. |
| Relais indisponible | Plan déjà accepté conservé ; nouvelles applications signalées comme indisponibles. |
| OpenAI | Simulation possible ; tentative d'action sans consentement refusée par le code. |

Le changement d'heure et les erreurs seront d'abord couverts en simulation, puis par les essais réels possibles. Tempo/Linky et l'initialisation Android ne sont pas réalisés dans cet audit du premier point.
