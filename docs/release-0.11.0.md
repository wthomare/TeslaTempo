# Android 0.11.0 — interface et connexion au relais

Cette préversion remplace la 0.10.0 avec la même signature. Installer l'APK par-dessus
l'édition de distribution conserve le profil, l'historique et le jeton enregistré.

## Interface

- Palette sombre monochrome, cartes sobres et navigation Recharge / Plan / Réglages.
- Batterie en premier, couleurs Tempo côte à côte, actions secondaires en liste.
- Détails des données et diagnostics privés accessibles à la demande.

## État de connexion

L'accueil et les réglages affichent un état explicite : relais non configuré,
vérification, connecté, jeton refusé, indisponible ou connexion à vérifier.
Toucher l'indicateur donne l'heure du dernier contrôle et un bouton de vérification.

Le contrôle authentifié appelle seulement `GET /v1/status`, à l'ouverture et au
retour au premier plan, puis chaque minute pendant que l'application est active.
Il ne contacte pas Tesla et ne réveille pas le véhicule. L'état connecté atteste
que le relais a répondu et accepté le jeton lors du contrôle ; il ne garantit pas
une disponibilité continue entre deux contrôles. Après une pause, il est revérifié.

Le statut du compte Tesla est séparé : une session enregistrée ne prouve pas que
ses droits sont encore valides, ni que le véhicule est joignable. Le contrôle est
compatible avec le relais 0.5.0 déjà déployé ; aucune migration du NAS n'est nécessaire.

## Essais sur téléphone

1. Installer la mise à jour et vérifier que le profil et le relais sont conservés.
2. Vérifier « Relais connecté », puis toucher l'indicateur pour voir le dernier contrôle.
3. Passer en mode avion et vérifier manuellement : un état indisponible doit apparaître.
4. Rétablir le réseau : le prochain contrôle doit confirmer la reconnexion.
5. Mettre l'application en arrière-plan puis revenir : elle doit revérifier le relais.
6. Parcourir Recharge, Plan, Réglages, le profil et l'historique, avec une taille de
   texte augmentée. Les données de démonstration restent explicitement identifiées.

Le contrôle automatique ne remplace pas une actualisation des données du véhicule.
Les commandes réelles restent désactivées dans cette version.
