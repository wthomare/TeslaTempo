# Tesla Tempo AI — Transmission à Codex

**Version 1.4 — 29 octobre 2026.** Cahier des charges consolidé. Fonctionnalités approuvées, faisabilité API à confirmer.

> **À Codex :** utiliser ce fichier comme contexte de référence. Initialiser une application Android native sans Home Assistant, avec un relais auto-hébergé par chaque utilisateur (conteneurs sur NAS ou VM Linux). Ne demander aucun secret dans la conversation et ne jamais committer de clés. D'abord démontrer les contraintes d'authentification/commandes Tesla ; développer avec mocks en attendant les accès. Ne pas confondre exigences et fonctionnalités déjà vérifiées.

## 1. Objectif et décisions acquises

Application Android **Tesla Tempo AI** pour optimiser la recharge **domestique** d'une Tesla selon EDF Tempo, la disponibilité du véhicule et les besoins. Notifications quotidiennes de nouveaux plans, historique et coûts, assistant GPT via API. Recharge Supercharger : **statistiques uniquement**, planification laissée à Tesla. Linky : **statistiques différées uniquement**, jamais de pilotage.

- **Android Kotlin + Jetpack Compose + Material 3**, APK privé ; pas de Home Assistant, DGX Spark ou SLM local.
- Moteur d'optimisation **déterministe** ; GPT facultatif, jamais autorisé à exécuter directement une commande véhicule.
- **Politique F20 définitive : « objectif prioritaire même après le départ », cible 80 %.** L'heure de départ est indicative, non une heure d'arrêt.
- Priorité aux tarifs économiques, mais **complément en heures pleines permis** pour continuer vers 80 %, y compris un jour rouge.
- Calendrier bureau, télétravail, week-end, vacances, exceptions ; recharge de nuit ou de journée selon disponibilité.
- Après publication couleur Tempo du lendemain : calcul, notification, actions **Appliquer / Modifier / Ignorer**.
- Historique domicile + Supercharger, statistiques, coûts et export CSV/JSON. Linky = données foyer différées, pas compteur Tesla.
- Paramètres configurables : capacité utile, courant de charge et disponibilité. Les exemples ne décrivent aucun utilisateur réel. Plages HC Tempo : **22 h–6 h**, fuseau `Europe/Paris`.

### Architecture de déploiement

Chaque utilisateur déploie son relais sur un hébergement compatible Docker.

Chaque utilisateur assure le déploiement, l'accès HTTPS, la maintenance et la configuration de ses propres comptes, clés et secrets. Il crée sa propre application Tesla Developer et réalise l'enregistrement et l'appairage nécessaires. Aucun service mutualisé ni identifiant de l'auteur n'est fourni.

Le projet prévoit de fournir une image de relais sans secrets, son Dockerfile public, un exemple Docker Compose et une configuration d'exemple sans valeurs réelles. Le relais V1 en lecture seule, le Dockerfile et Compose sont disponibles ; l’image se construit localement et n’est pas publiée dans un registre. Le proxy de signature reste à développer. Voir [le guide V1](relay-v1.md). Les secrets sont injectés au déploiement, jamais inclus dans Git, l'image ou l'APK. L'application Android doit permettre de configurer l'URL HTTPS du relais de l'utilisateur et de s'y authentifier.

Voir [le contrat de déploiement auto-hébergé](self-hosting.md) pour la répartition des responsabilités et l'exposition réseau.

## 2. Périmètre fonctionnel

| ID | Fonction | Priorité |
|---|---|---|
| F01 | Tableau de bord SoC, charge, couleur Tempo, plan, coût | P0 |
| F02 | Sessions domestiques : énergie, durée, coût, qualité | P0 |
| F03 | Optimisation EDF Tempo | P0 |
| F04 | Assistant GPT via API et outils contrôlés | P0 |
| F05 | Paramètres et sécurité | P0 |
| F06 / F12 | Linky/Enedis pour statistiques différées seulement | P1 |
| F07 | Synchronisation couleur Tempo du lendemain | P0 |
| F08 / F09 | Notification du plan, validation/modification/refus | P0 |
| F10 | Calendrier mobilité bureau/TT/week-end/vacances | P0 |
| F11 | Trajets et exceptions ponctuelles | P1 |
| F13 | Suggestions d'habitudes, opt-in | P2 |
| F14–F17 | Import Supercharger, montants, historique unifié, comparatifs | P0 |
| F18–F19 | Factures PDF et historique ancien Supercharger | P1 |
| F20 | Continuité de recharge vers 80 % après départ et HC | P0 |
| F21 | Calendrier Android facultatif | P1 |
| F22 | Exports CSV et JSON | P0 |

## 3. Règles de planification et continuité F20

1. Cible **80 %** par défaut (configurable). Départ indicatif : il **ne provoque pas l'arrêt**.
2. Calculer énergie à ajouter, puissance réelle et pertes estimées, périodes tarifaires et disponibilité.
3. Favoriser les heures creuses ; **ne pas arrêter définitivement sous 80 % à 6 h**.
4. Autoriser complément en HP, même rouge ; afficher coût additionnel et date/heure estimées.
5. L'application peut reporter la recharge à une période moins chère si cohérent avec la disponibilité, sans prétendre garantir 80 % si voiture débranchée.
6. **La Tesla doit pouvoir exécuter seule le programme accepté**. Si un plan multi-créneaux exige une commande Android à heure exacte, ne pas le promettre : proposer par défaut un **mode de secours simple** (charge continue vers 80 % ou programmation native effectivement supportée), même plus cher.
7. Limite de courant initiale **10 A** ; ne jamais la dépasser. Appliquer limite de charge 80 %.
8. Respecter les programmations manuelles Tesla et ne pas les écraser sans confirmation.
9. Ne piloter que la recharge **au domicile**, avec identification de lieu consentie ; ne pas transmettre la localisation précise à GPT.
10. Commande réussie seulement après confirmation et, si possible, relecture de l'état ; après timeout, relire avant de réessayer.

**Cas d'échec :** débranchée → attente/notification ; véhicule hors ligne → dernier plan confirmé et retries bornés ; API Tesla erreur/quota → état non appliqué ; timeout → relecture ; SoC ≥80 % → aucune commande ; puissance moindre → réestimation ; fin HC <80 % → complément autonome ou secours explicite ; téléphone éteint/Doze → seul le programme déjà accepté par Tesla est fiable ; départ anticipé → recalcul au retour ; couleur future inconnue → scénario conservateur sans invention ; HP rouge → surcoût affiché ; changement manuel → respect et réconciliation ; révocation OAuth → commandes suspendues ; GPT indisponible → fonctionnement classique préservé.

États de plan suggérés : `DRAFT`, `VALIDATED`, `APPLYING`, `APPLIED_CONFIRMED`, `FAILED`, `STALE`, `SUPERSEDED`, `UNKNOWN_OUTCOME`.

## 4. EDF Tempo, calendrier et notifications

- Source publique RTE/EDF (ou intermédiaire fiable), cache et provenance. Six tarifs TTC bleu/blanc/rouge × HP/HC **versionnés avec dates de validité** ; jamais de tarifs figés sans date.
- Gérer les frontières 22 h/6 h, jour tarifaire, minuit, changement d'heure été/hiver, fuseau Europe/Paris.
- Vérifier couleur du lendemain après publication (habituellement à partir de 11 h) ; tentative indicative vers 11 h 15, retries si indisponible.
- **WorkManager n'est pas exact à la minute** : notifier seulement après récupération et calcul effectifs.
- Notification : couleur, SoC, objectif 80 %, créneaux nuit/jour, durée, coût, surcoût HP, actions Appliquer/Modifier/Ignorer.
- Profils `OFFICE`, `REMOTE`, `WEEKEND`, `VACATION`, `CUSTOM`, semaine type, exceptions, congés, horaires départ/retour ; lecture du calendrier Android facultative et consentie.
- Ne jamais inventer une couleur Tempo future non encore publiée.

## 5. Historique et statistiques

**Domicile :** début/fin, SoC, énergie, puissance si disponibles, coût par période tarifaire. Qualité `MEASURED`, `ESTIMATED`, `PARTIAL` ; données Tesla ≠ nécessairement énergie facturée au compteur.

**Supercharger :** lecture seule, sessions, kWh, durée, lieu, coût facturé, devise, remises/frais, facture PDF si accessible. Vérifier l'accès API d'un compte Tesla particulier ; pagination, backfill, dédoublonnage, import manuel de secours. Aucune planification Supercharger.

**Linky :** consentement et récupération **différée** des données de consommation du foyer ; aucune commande de recharge. Linky ne permet pas seul d'isoler précisément la consommation de la voiture.

**Indicateurs :** dépenses domicile / Supercharger / total, kWh, €/kWh, par semaine/mois/année, HP/HC, couleurs, économies vs scénario de référence explicite, coût/100 km seulement si kilométrage fiable. Conserver provenance et degré de confiance.

## 6. Architecture Android

- Kotlin, Compose, Material 3, Navigation, MVVM/Clean Architecture pragmatique.
- Room/SQLite pour historique, tarifs, plans, audit, conversations ; DataStore pour préférences **non secrètes**.
- Android Keystore et stockage protégé pour jetons/clefs ; aucun secret dans logs, code ou base non chiffrée.
- Retrofit/OkHttp, coroutines/Flow, WorkManager, notifications, JUnit, MockWebServer, tests Compose.
- `minSdk` proposé Android 13/API 33 (à valider), `targetSdk` conforme aux règles en vigueur.
- Modules logiques : `app`, `dashboard`, `planner`, `mobility`, `history`, `statistics`, `assistant`, `settings`, `tesla-client`, `tempo-client`, `linky-client`, `charging-engine`, `cost-engine`, `data`, `security`. Éviter une surmodularisation prématurée.

Entités Room proposées : `Vehicle`, `VehicleSnapshot`, `MobilityProfile`, `MobilityException`, `TempoDay`, `TariffVersion`, `ChargingPlan`, `PlanSlot`, `CommandAudit`, `ChargeSession`, `ChargeSample`, `SuperchargerInvoice`, `LinkyReading`, `AgentConversation`, `AppSettings`, `SyncState`. Stocker horodatages UTC, provenance, devise, qualité, version tarifaire, identifiants de dédoublonnage. Jamais de tokens dans Room en clair.

Interfaces à concevoir : `VehicleGateway`, `TempoGateway`, `ChargingOptimizer`, `SuperchargerHistoryGateway`, `CostCalculator`, `AiAssistantGateway`. L'application d'un plan doit pouvoir renvoyer « non représentable par Tesla » et proposer un secours.

## 7. Intégrations : vérifications indispensables

### Tesla Fleet API — P0 bloquant

1. Créer application Tesla Developer, OAuth, scopes minimaux, domaine et redirect URI mobile.
2. Examiner exigences de client confidentiel, éventuel `client_secret`, flux OAuth mobile et contraintes de signature.
3. Vérifier publication de la **clé publique** à `https://DOMAINE/.well-known/appspecific/com.tesla.3p.public-key.pem` si toujours requise ; **jamais la clé privée**.
4. Distinguer clé d'enregistrement de l'application, clé virtuelle et secrets OAuth ; ne pas présumer interchangeabilité.
5. Valider appairage de la clé virtuelle, signature des commandes depuis Android et capacités exactes de programmation native (créneaux, récurrence, début/fin, reprise).
6. Test initial **lecture seule** (SoC, état, sessions Supercharger), puis test de commande contrôlé.
7. Minimiser polling/réveils, vérifier quotas, facturation, disponibilité des endpoints pour un particulier.

Documentation : https://developer.tesla.com/docs/fleet-api

### OpenAI API

- Compte API **non encore créé** ; facturation distincte de ChatGPT.
- Utiliser Responses API, function/tool calling, modèle effectivement accessible. Ne pas supposer que « GPT-6 Luna » est un identifiant d'API.
- Outils lecture : `get_vehicle_status`, `get_tempo_calendar`, `get_tariff_prices`, `get_charging_history`, `get_charging_statistics`, `simulate_charging_plan`.
- Outils action : `validate_charging_plan`, `apply_charging_plan`, `cancel_charging_plan`, soumis à validation Kotlin, consentement et audit.
- **Ne pas inclure une clé OpenAI statique dans l'APK**. Solution retenue : **relais HTTPS auto-hébergé par l’utilisateur**, avec authentification et quotas. La clé OpenAI personnelle, facultative, est fournie uniquement au relais au déploiement.
- GPT ne reçoit ni secrets Tesla ni localisation précise par défaut ; son indisponibilité ne bloque pas la recharge.

Documentation : https://platform.openai.com/docs/

### Tempo / Linky

- Valider source des couleurs, tarifs, conditions d'accès et publication ; garder mode manuel de secours pour les prix.
- Linky/Enedis uniquement P1, différé et statistique ; intégration avec consentement et séparation stricte du moteur de planification.

## 8. UX / écrans

1. Onboarding : Tesla OAuth, permissions, domicile/10 A, tarifs, GPT optionnel.
2. Accueil : batterie, branchement, Tempo aujourd'hui/demain, plan, coût.
3. Planificateur : cible 80 %, politique F20, créneaux, durée, coût, confirmation.
4. Mobilité : semaine type, TT, bureau, vacances, exceptions.
5. Historique : sessions domicile/Supercharger, détails et factures.
6. Statistiques : coûts, kWh, graphiques, qualité des données, exports.
7. Assistant GPT : chat en français, simulation, validation des actions.
8. Paramètres : autorisations, courant, tarifs, sécurité, diagnostic, déconnexion.

## 9. Tests d'acceptation

- OAuth et révocation Tesla ; lecture SoC sans polling excessif.
- Signature et application d'une programmation **confirmées sur véhicule réel**.
- Refus explicite des plans non représentables par la Tesla.
- Optimisation correcte pour 6 tarifs, couleur inconnue, 10 A, DST, disponibilité nuit/jour.
- **F20** : complément HP possible, pas d'arrêt définitif sous 80 % à 6 h, coût supplémentaire affiché.
- Téléphone éteint : plan Tesla déjà confirmé reste opérationnel ; pas de promesse de nouvelle commande.
- Notification après données effectivement obtenues ; pas d'heure exacte garantie.
- Sessions unifiées sans double comptage ; coûts mesurés vs estimés distingués.
- Supercharger lecture seule ; Linky différé sans pilotage ; GPT facultatif.
- Tests unitaires, mocks réseau, tests instrumentés, export CSV/JSON et APK signé.

## 10. Plan de développement Codex

**Étape 0 — Faisabilité :** lire les docs officielles actuelles Tesla/OpenAI, produire `docs/feasibility.md` listant endpoints/scopes, OAuth, signature, capacités scheduling, limites API, coûts, risques et alternatives. **Ne pas inventer de compatibilité.**

**Étape 1 — Socle :** initialiser Gradle/Kotlin/Compose/Room, DI, navigation, thème, CI, simulateurs Tesla/Tempo et tests.

**Étape 2 — Moteur :** modèles de domaine, calcul coût Tempo, optimisation F20, profils mobilité, tests exhaustifs et stratégie de secours.

**Étape 3 — UX :** accueil, calendrier, planificateur, notifications, historique et statistiques sur données simulées.

**Étape 4 — Tesla réelle :** onboarding OAuth, lecture seule, historique Supercharger, appairage/signature, programmation testée, audit.

**Étape 5 — OpenAI :** intégration au relais auto-hébergé sécurisé, Responses API, outils et confirmations.

**Étape 6 — Linky et finition :** données différées, exports, tests end-to-end, APK signé et documentation.

### Première demande à exécuter par Codex

> « À partir de ce cahier des charges, commence par auditer les points bloquants Tesla Fleet API et OpenAI pour une application Android avec relais auto-hébergé par l’utilisateur. Crée `docs/feasibility.md` avec sources officielles et statut VALIDÉ / À TESTER / BLOQUANT. Puis initialise un projet Android Kotlin/Compose compilable, un faux client Tesla, un faux fournisseur Tempo, les modèles de domaine, un moteur de planification F20 testé et un README de démarrage. Ne demande aucun secret et n'effectue aucune commande réelle sur le véhicule. »

## 11. Questions ouvertes (ne pas trancher sans vérification)

- Vérifier les datasets, les permissions et HTTPS sur chaque hébergement.
- Valider le flux OAuth et les commandes signées avec le relais auto-hébergé ; confirmer l’acceptation de l’application et du domaine par Tesla.
- Quelles fonctions de programmation sont réellement disponibles sur la version logicielle du véhicule cible ?
- L'historique/facturation Supercharger est-il accessible au compte particulier via l'API envisagée ?
- Valider l’injection des secrets et le stockage persistant protégé sur l’hébergement choisi.
- Quelle source tarifaire/version de prix TTC et quel mécanisme d'import Linky retenir ?

**Fin du document — ne pas demander de mots de passe, jetons, clés privées ou clés API dans le chat.**
