# Conduite — V1 BLE expérimentale (Android 0.13.0)

Le module Conduite lit directement la voiture via Bluetooth Low Energy. Il n'utilise
ni le relais NAS, ni OAuth Tesla, ni Fleet API, ni Fleet Telemetry. Les fonctions
existantes de recharge continuent à utiliser leur relais. Aucun appel réseau Tesla,
aucun réveil explicite et aucune commande de charge ne sont ajoutés par ce module.
La vitesse n’est ni affichée ni exploitée par ce module ; aucun GPS téléphone n’est utilisé.

## Préparation à l'arrêt

1. Installer l'APK signée par-dessus la version précédente, puis ouvrir **Conduite**.
2. Utiliser l'aperçu de démonstration pour découvrir le bloc batterie/autonomie à droite. Les chiffres sont fictifs ; cet aperçu n'active pas le BLE.
3. Renseigner le VIN de 17 caractères de la voiture. Le VIN est chiffré sur le téléphone,
   exclu des sauvegardes ; il ne doit pas être partagé dans une issue ou une capture.
4. Autoriser **Appareils à proximité** et **Notifications**, puis activer Bluetooth.
5. La première fois, toucher **Ajouter une clé de lecture à la voiture**, lire la
   confirmation et envoyer la demande à proximité immédiate du véhicule.
6. Présenter une carte-clé autorisée sur le lecteur et confirmer sur l'écran Tesla,
   selon les indications du véhicule. Seul le rôle **Vehicle Monitor** est demandé.
   L'envoi de la demande ne prouve pas son acceptation. Aucun rôle Driver/Owner n'est demandé.
7. Toucher **Connecter en BLE**. L'état connecté n'apparaît qu'après validation de la session.
   Vérifier les données à l'arrêt face à l'écran Tesla, avant tout essai du cockpit.

La clé BLE est générée dans Android Keystore avec le seul usage ECDH, sans export
possible de sa partie privée. Elle est distincte de la clé Fleet API du relais et
ne remplace pas la clé téléphone Tesla officielle. Le rôle Monitor ne permet pas
le déverrouillage. Une désinstallation efface la clé locale : supprimer ensuite la
clé devenue inutile depuis la gestion des clés du véhicule, puis refaire l'appairage.

## Cockpit et Waze

- Connecter le BLE, puis toucher **Lancer Waze avec le cockpit**.
- Autoriser la superposition Android lorsque demandée, revenir à TeslaTempo puis
  retoucher le bouton. Waze doit être installé ; aucun téléchargement automatique.
- Une petite fenêtre non tactile et non focusable est affichée en haut à droite. Aucun service
  d'accessibilité ni analyse du contenu de Waze n'est utilisé. Le téléphone peut être
  placé en paysage ; TeslaTempo n'impose pas l'orientation à Waze.
- Ajuster taille, décalage vertical et opacité à l'arrêt, session arrêtée. L'opacité
  est plafonnée à 75 % pour respecter le passage des touches Android.
- Vérifier que les indications de navigation restent visibles. L'application ne sait
  pas où Waze place ses bandeaux, et ne garantit pas leur absence de recouvrement.
- **Arrêter le cockpit**, dans la notification ou dans Conduite, ferme le BLE et la
  fenêtre et supprime les valeurs. Retirer TeslaTempo des tâches récentes arrête
  aussi la session. Aucun redémarrage automatique au démarrage du téléphone.

Le cockpit est un affichage complémentaire, pas un remplacement de l'instrumentation
homologuée du véhicule. Configurer et manipuler le téléphone à l'arrêt.

## Données et destination

- Batterie : `GetChargeState`, toutes les 30 secondes, pourcentage si présent.
- Autonomie : `battery_range`, miles convertis en km, jamais déduite du WLTP.
  Batterie/autonomie deviennent des tirets après 60 secondes.
- La fraîcheur affichée mesure le temps depuis réception de la réponse sur le téléphone ;
  elle ne certifie pas l'âge interne de la mesure Tesla. Les données restent en mémoire.
- **Synchroniser la destination Tesla** déclenche une lecture ponctuelle de
  `DriveState` et retient la destination, si son nom et ses coordonnées sont valides. Elle expire après 60 secondes.
  Les coordonnées (0, 0), ambiguës, sont rejetées.
- **Ouvrir cette destination dans Waze** nécessite un appui explicite. Waze calcule
  son itinéraire ; ni tracé Tesla ni étapes Superchargeur ne sont transférés.
- Aucune destination n'est enregistrée dans l'historique ni envoyée aux logs/NAS.
  Seul un appui sur le bouton Waze transmet la destination à cette application.

## Limites à confirmer sur véhicule réel

Cette version n'a pas été validée sur une Tesla physique. L'émulateur valide le code,
les écrans et le Keystore, pas la compatibilité radio, le rôle Monitor accepté par le
firmware, la disponibilité des données ou la coexistence avec les clés téléphone.

Tesla indique une limite de trois connexions BLE simultanées partagées avec les clés.
La V1 utilise une seule connexion. Si elle échoue, arrêter la session et réessayer ;
il n'y a pas de boucle de reconnexion automatique ni de repli API payant.

Une panne, une révocation d'autorisation ou une perte de session retire le cockpit.
Une réponse `DriveState` trop grande (défaut Tesla MTU 25) laisse la destination
indisponible et laisse la lecture batterie continuer. Aucun faux chiffre n'est injecté.
Les réponses sans chiffrement, mal authentifiées, expirées ou ne correspondant pas
à la demande sont rejetées. Le HMAC de session du protocole Tesla protège l'intégrité,
mais n'est pas un certificat d'identité du véhicule ; voir la spécification officielle.

Le transport BLE Android, l'appairage, la rotation, le passage des gestes au travers
de la fenêtre et la visibilité au-dessus de Waze doivent être vérifiés sur le téléphone
physique et la voiture avant de considérer cette V1 comme validée en conduite.

Aucune lecture périodique de `DriveState` n’est effectuée. Cet état n’est demandé
que pour récupérer la destination après un appui explicite. Tesla peut inclure
la vitesse dans sa réponse ; elle est ignorée, sans stockage ni affichage.

## Sources et maintenance

Les schémas Protobuf officiels sont conservés sans modification dans
`apps/android/tesla-protocol/src/main/proto`, avec leur licence et le commit source
sous `apps/android/third-party/tesla`. Le module JVM produit les classes Protobuf lite ;
aucun générateur ou serveur Go n'est requis sur le téléphone.

- [Protocole Tesla et vecteurs de test](https://github.com/teslamotors/vehicle-command/blob/main/pkg/protocol/protocol.md)
- [Lecture des états en BLE](https://github.com/teslamotors/vehicle-command/blob/main/pkg/vehicle/state.go)
- [Appairage d'une clé locale](https://github.com/teslamotors/vehicle-command/blob/main/pkg/vehicle/security.go)
- [Limite de taille des réponses avec navigation active](https://github.com/teslamotors/vehicle-command/issues/472)
- [Permissions Bluetooth Android](https://developer.android.com/develop/connectivity/bluetooth/bt-permissions)
- [Services de premier plan Android](https://developer.android.com/develop/background-work/services/fgs/service-types)
- [Waze Deep Links](https://developers.google.com/waze/deeplinks)
