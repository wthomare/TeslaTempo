# Historique local des simulations

Depuis Android 0.4.0, un calcul personnel ou une démonstration peut être conservé
avec **Enregistrer la simulation**. L'enregistrement est explicite : ouvrir un
écran ou lancer un calcul ne crée pas d'entrée automatiquement.

## Consulter un calcul

Depuis l'accueil, ouvrir **Historique des simulations**, puis **Consulter la
simulation**. Chaque entrée indique son origine : démonstration fictive ou
simulation personnelle. Elle conserve les paramètres de batterie, l'horizon,
le départ indicatif, les créneaux, l'estimation économique et le secours.

Les prix utilisés, leur source et leur identifiant sont conservés avec les créneaux,
y compris les couleurs inconnues et leur estimation conservatrice. Une mise à
jour de Tempo ou du profil ne recalcule pas une archive. Les coûts restent des
estimations, sans constituer des dépenses mesurées ou des économies réalisées.

Les résultats sont consultables hors connexion, même après fermeture de
l'application. Pour préparer une nouvelle recharge, revenir au planificateur et
confirmer les données actuelles. Une archive ne peut pas envoyer de commande à
la voiture. Les calculs bloqués par une absence ou une programmation manuelle
ne sont pas enregistrables comme plans.

## Vie privée et suppression

L'historique est conservé dans un DataStore privé de l'application, séparé du
profil et des identifiants du relais. Il est exclu des sauvegardes et transferts
Android. Il n'est transmis ni au relais, ni à Tesla, ni à un service d'IA.

Il ne contient pas de VIN, d'identifiant véhicule, de coordonnées, d'URL du relais,
de jeton ou de semaine type. Les dates, créneaux et paramètres de simulation
restent néanmoins des données personnelles. Le stockage est protégé par les
permissions Android ; aucun chiffrement applicatif supplémentaire de l'historique
n'est revendiqué.

**Supprimer cette simulation** et **Effacer l'historique** demandent confirmation.
Ces actions conservent le profil, le cache Tempo et la connexion au relais.
Le passage en mode démonstration n'efface pas l'historique. La désinstallation
ou l'effacement des données Android le supprime.

La limite est de **50 simulations**. Une fois atteinte, il faut supprimer une
entrée avant d'en ajouter une autre : aucune ancienne simulation n'est effacée
automatiquement. Enregistrer deux fois le même résultat affiché ne le duplique pas.
Un nouveau calcul peut produire une nouvelle entrée, même avec les mêmes paramètres.

## Intégrité et limites

Le format local est versionné. Les dates, chiffres, bornes et cohérence énergétique
sont vérifiés à la lecture. Un historique illisible ou d'une version non prise en
charge n'est pas remplacé silencieusement. L'écran propose son effacement explicite.
Les écritures sont atomiques ; les données précédentes sont conservées si une
écriture échoue.

Cette version ne fournit pas d'historique des recharges Tesla, d'export ou de
synchronisation entre appareils. Les tests utilisent uniquement des données
synthétiques et couvrent les archives, la persistance et les confirmations de
suppression.
