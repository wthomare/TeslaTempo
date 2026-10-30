# Application Android Tesla Tempo — prototype 0.3.0

Application native Kotlin, Jetpack Compose et Material 3. Android 13 minimum (API 33), compilation/cible API 36. APK privé de développement, aucune publication sur Google Play.

## Ce qui fonctionne

- Tableau de bord en français, thème sombre anthracite, démonstration hors connexion au premier démarrage.
- Connexion au relais V1 : URL configurable, authentification Bearer, sélection d'un véhicule de la première page, batterie, limite, état de charge et courant demandé.
- Rafraîchissement manuel et erreurs explicites : jeton refusé, quota, réseau, réponse invalide. Aucun polling automatique ni commande véhicule. Une lecture est faite à l'ouverture si une connexion a été enregistrée.
- Provenance affichée : démonstration locale, relais simulé ou relais Tesla. Heure de réception distincte de la mesure ; aucune batterie par défaut en cas de mesure absente.
- URL et jeton chiffrés AES-GCM avec une clé Android Keystore ; sauvegarde et transfert de ces préférences exclus. Retour au mode démonstration = effacement de la connexion locale, sans révocation côté relais.

**Nouveau :** [moteur Tempo/F20 et simulateur](../../docs/planner-f20.md), comparaison avec une charge continue de secours, coûts HP et cas de disponibilité insuffisante. Depuis l’accueil : « Simuler un plan F20 ». Les tarifs et couleurs du simulateur sont fictifs, indépendants du véhicule.

**Nouveau en 0.3.0 :** [couleurs et tarifs Tempo publiés, cache DataStore, profil personnel et calendrier](../../docs/tempo-personal.md). L'accueil propose « Mon profil et calendrier », « Mon plan personnel » et les données Tempo avec leur provenance. Le profil exige une capacité de batterie renseignée par l'utilisateur ; aucune capacité de Model Y n'est supposée.

**Non livré :** OAuth dans Android, application des plans, historique, notifications et assistant. Les profils et le cache sont sauvegardés avec DataStore ; Room sera ajouté avec l'historique. Le socle emploie des ViewModels, StateFlow et une injection manuelle.

## Construire et ouvrir

Ouvrir **ce dossier** (`apps/android`) dans Android Studio. Installer SDK Platform 36 et Build Tools 36.0.0, utiliser JDK 17 minimum compatible avec Gradle 9.3.1 (par exemple JBR 25). Kotlin 2.2.10 est fourni par AGP 9.1.1. Le wrapper versionné télécharge Gradle et vérifie son SHA-256.

```bash
cd apps/android
# ANDROID_HOME doit désigner le SDK ; ou renseigner sdk.dir dans local.properties (ignoré).
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

L'APK debug utilise `org.teslatempo.debug`. Le build release n'est pas signé ; aucune clé de signature n'est versionnée. Le wrapper Windows est également fourni.

Configurer le SDK et le JDK sur chaque poste. Si un proxy est nécessaire, conserver ses paramètres dans la configuration Gradle locale, hors dépôt.

## Essayer avec le relais Docker simulé

Depuis la racine du dépôt :

```bash
python3 scripts/init-local.py
docker compose up -d --build --wait
python3 scripts/check-running.py
```

Dans l'application : **Réglages**, URL et jeton personnel du relais (fichier local `secrets/relay_api_token`), puis **Connecter le relais**. Ne transmettre aucun jeton dans une issue ou dans la conversation.

- Émulateur Android : utiliser `http://10.0.2.2:8080` dans l'APK **debug**.
- Appareil USB, ou émulateur dont le réseau est perturbé par le proxy du poste : `adb reverse tcp:8080 tcp:8080`, puis `http://127.0.0.1:8080` dans l'APK debug. Ce tunnel ADB rejoint le port local sans exposer le relais sur le LAN.
- Relais personnel déployé : URL racine **HTTPS**, certificat reconnu par Android. Pas de chemin, de paramètres ni d'identifiants dans l'URL.

HTTP n'est permis que dans le build debug vers les trois hôtes locaux `10.0.2.2`, `127.0.0.1` et `localhost`, pour des jetons de test. Release exige HTTPS partout. Les redirections sont refusées et aucun certificat TLS n'est accepté arbitrairement. Le proxy du poste n'est pas configuré dans l'application.

La connexion n'est enregistrée qu'après lecture réussie du relais. Pour changer d'adresse, ressaisir le jeton : le jeton précédent n'est jamais envoyé automatiquement au nouveau serveur. Un échec masque les anciennes mesures. Le relais peut fournir des données en cache durant 30 s. Un rafraîchissement peut effectuer une lecture Tesla si le relais est en mode réel ; il ne réveille pas explicitement la voiture.

## Tests

```bash
./gradlew :app:testDebugUnitTest :app:lintDebug
# Un émulateur ou un appareil de test doit être connecté :
./gradlew :app:connectedDebugAndroidTest
```

Les tests JVM MockWebServer couvrent le contrat du relais, le jeton, la sélection, les données absentes/invalides, les erreurs, les redirections et HTTPS. Les tests instrumentés vérifient le parcours Compose et le stockage Keystore. Utiliser un appareil de test : ils effacent la configuration locale de l'application debug.

Les tests utilisent des données synthétiques et un appareil dédié. Les journaux et comptes rendus propres à une installation restent hors du dépôt public.

La CI construit l'APK, exécute les tests JVM et lint ; les tests instrumentés restent locaux pour cette version. Le workflow GitHub n'a pas encore été exécuté pour ce socle.

## Organisation

```text
app/src/main/java/org/teslatempo/
  domain/       Modèles de lecture, interface VehicleGateway, démonstration
  network/      Client du relais, validation URL et traitement des erreurs
  security/     Stockage chiffré Android Keystore
  planner/      Calcul Tempo/F20 Kotlin pur et scénarios fictifs
  personal/     Profil, calendrier de mobilité, sérialisation et DataStore
  tempo/        Client HTTP public, prix datés et cache de couleurs
  ui/           ViewModel et écrans Compose
app/src/test/          Tests JVM du contrat réseau
app/src/androidTest/   Tests Compose et Keystore
app/src/debug/         Exception HTTP strictement locale
```

Sources techniques : [compatibilité AGP 9.1.1](https://developer.android.com/build/releases/agp-9-1-0-release-notes), [sécurité réseau Android](https://developer.android.com/privacy-and-security/security-config), [état Compose](https://developer.android.com/develop/ui/compose/state-hoisting). Versions choisies pour reproduire la chaîne validée sur ce poste, pas pour revendiquer les toutes dernières bibliothèques.

Références : [spécification](../../docs/specification.md), [relais V1](../../docs/relay-v1.md), [organisation](../../docs/repository.md).
