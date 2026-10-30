# Installer et mettre à jour Android

## Deux éditions distinctes

- Développement : `org.teslatempo.debug`, APK `app-debug.apk`, données de test.
- Distribution : `org.teslatempo`, APK signé avec une identité privée durable.

Les deux éditions peuvent cohabiter. Passer de debug à distribution ne transfère
pas les données : reconnecter le relais et renseigner le profil dans la nouvelle
édition. Une mise à jour de la même édition conserve ses données si la signature
est identique et le `versionCode` augmente. Ne pas désinstaller pour mettre à jour.
Les secrets du relais sont saisis sur le téléphone, jamais inclus dans l'APK.

## Préparer la signature une seule fois

Prérequis : JDK, Android SDK et build-tools 36.0.0, environnement de compilation
Android configuré comme décrit dans [le guide Android](../apps/android/README.md).

```bash
python3 scripts/android-signing.py init
```

Le script crée une clé RSA et un fichier `signing.json` dans
`~/.config/teslatempo/android-signing`, dossier 700 et fichiers 600. Il refuse
un dossier existant. Aucun secret ne passe dans les arguments de commande.
Le nom du certificat est générique et ne contient pas l'identité du développeur.

**Sauvegarder ce dossier privé hors du poste avant de distribuer l'APK** : sans
la clé et son mot de passe, il sera impossible de mettre à jour une installation
signée avec elle. Conserver cette sauvegarde chiffrée, hors du dépôt et des artefacts CI.
Ne pas régénérer une clé sur chaque poste. Après restauration sur un autre poste,
adapter seulement le chemin `storeFile` du fichier privé `signing.json`.

## Construire et vérifier

```bash
python3 scripts/android-signing.py build
```

Le script compile `release`, exécute `lintRelease`, vérifie la signature avec
`apksigner`, puis produit dans `apps/android/app/build/distribution/` :

- `teslatempo-release.apk` ;
- `SHA256SUMS`, empreinte du fichier ;
- `SIGNATURE.txt`, empreinte publique SHA-256 du certificat.

Les tests sont à exécuter avant distribution :

```bash
cd apps/android
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebugAndroidTest
```

L'APK est remis explicitement à l'utilisateur ; aucune publication Play Store,
aucun téléchargement automatique et aucune installation silencieuse. La clé de
signature n'est pas confiée à GitHub Actions. L'APK debug reste un outil de test.

Sur le téléphone, autoriser ponctuellement l'installation depuis l'application
qui ouvre l'APK, puis installer. Pour les mises à jour, vérifier l'origine et la
signature identique ; Android refusera une signature différente.

## Rappels et confidentialité

Depuis une simulation **personnelle** enregistrée, un rappel facultatif propose
une vérification 15 minutes avant le premier créneau économique. Il remplace le
rappel précédent. Les simulations fictives n'offrent pas de rappel.

Le rappel n'exécute aucune commande, n'appelle ni Tesla ni ChatGPT et ne recharge
pas les données Tempo en arrière-plan. Android peut le retarder, notamment en
économie d'énergie. Il ne faut pas s'y fier pour démarrer une recharge à l'heure.
La permission de notification est demandée uniquement après action explicite.
Un refus laisse le reste de l'application utilisable. La suppression du plan
annule son rappel ; un rappel expiré après redémarrage est abandonné. Au retour,
recalculer avec les données actuelles plutôt qu'exécuter une ancienne simulation.

Les données locales sont exclues des sauvegardes Android. Une désinstallation
supprime le profil, l'historique et les identifiants locaux ; les relevés conservés
sur le NAS restent indépendants. L'export CSV est une sauvegarde lisible des relevés,
pas un format d'import du profil ou de restauration de l'application.

Sources : [signature Android](https://developer.android.com/studio/publish/app-signing),
[permission de notification](https://developer.android.com/develop/ui/views/notifications/notification-permission).
