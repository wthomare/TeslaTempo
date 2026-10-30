# Tempo réel, profil et calendrier — Android 0.3.0

## Source et récupération

L'application utilise les endpoints publics HTTPS de [calendrier-tempo.fr](https://www.calendrier-tempo.fr/api-tempo), un **service indépendant**, sans compte ni clé. Attribution : calendrier-tempo.fr, licence [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/). Ce service relaie les couleurs publiées et les tarifs ; il ne constitue pas une connexion au compte EDF de l'utilisateur.

- `/api/today` : couleur datée du jour et six tarifs TTC, avec date d'effet.
- `/api/tomorrow` : couleur du lendemain, ou valeur inconnue tant qu'elle n'est pas publiée.
- `/api/history?days=2` : seule la couleur de la veille est retenue, pour la période avant 6 h.

**Aucun endpoint de prévision n'est consulté.** Le client contrôle les dates, le code et le nom de couleur, l'unité des prix, les horaires HC/HP, la présence des six prix et leur domaine numérique. Une réponse incohérente n'écrase pas le cache du domaine concerné. Une réponse explicite « non publiée » reste inconnue. La panne d'un endpoint n'empêche pas de conserver les autres résultats valides.

Les appels utilisent un client dédié, sans en-tête d'authentification Tesla ou du relais. Les paramètres et horaires personnels ne sont jamais envoyés au fournisseur Tempo. HTTPS conserve la vérification normale des certificats ; les redirections et réponses trop volumineuses sont refusées.

Le 29 octobre 2026, les réponses réelles du fournisseur ont été examinées et ses six prix comparés à la [grille EDF officielle](https://particulier.edf.fr/content/dam/2-Actifs/Documents/Offres/Grille_prix_Tarif_Bleu.pdf), applicable au 1er août 2026 : bleu HC/HP 0,1356/0,1654, blanc 0,1536/0,1921, rouge 0,1615/0,7295 €/kWh TTC. **Ces valeurs ne sont pas figées dans le code de production** : elles sont téléchargées. Les fixtures des tests sont datées et n'alimentent pas l'application.

## Cache et fraîcheur

Le cache et les réglages non secrets sont stockés par Jetpack DataStore dans les fichiers privés de l'application, exclus des sauvegardes cloud et des transferts d'appareil. Le jeton du relais reste dans son stockage chiffré Keystore séparé.

Chaque couleur possède une date de jour et une date de contrôle. L'écran affiche les dates du cache, y compris hors connexion ; une couleur de la veille ne devient jamais automatiquement celle du jour suivant. Avant 6 h, la couleur tarifaire de la veille est explicitement distinguée des couleurs calendaires aujourd'hui/demain. Les entrées anciennes sont élaguées lors d'une synchronisation.

L'application tente une actualisation à l'ouverture ou au retour au premier plan si le contrôle du jour remonte à plus de 30 minutes. Elle vérifie aussi ce besoin chaque minute **pendant qu'elle est au premier plan**, notamment pour le changement de date. Un délai minimum de deux minutes sépare les tentatives au cours d'une session. Le bouton « Actualiser Tempo » utilise le même garde-fou. Aucun service d'arrière-plan, réveil exact ou notification n'est promis dans cette version.

Une erreur réseau conserve les valeurs précédemment enregistrées et affiche un message. Sans tarifs, le plan personnel est bloqué. L'API ne fournit pas de date de fin de validité tarifaire : le modèle conserve la date d'effet publiée et applique une **échéance locale de recontrôle**, au début du jour situé sept jours après le dernier contrôle. Ce n'est pas une date d'expiration annoncée par EDF. Tout créneau dépassant cette échéance est refusé par le moteur. Une horloge revenue avant le contrôle est aussi refusée.

Les couleurs futures non publiées sont calculées à la borne de prix haute de la période, avec mention d'estimation conservatrice. La validité contractuelle reste à comparer à son contrat/compteur. L'abonnement n'est pas inclus dans les coûts de recharge.

## Paramètres personnels

Accueil → **Mon profil et calendrier** :

- Capacité utile estimée, initialement **à renseigner** : aucune capacité n'est déduite du modèle de véhicule.
- Cible de batterie, 80 % par défaut ; courant de 1 à 10 A ; rendement estimé de 10 à 100 % (90 % par défaut). Le calcul suppose du monophasé 230 V, sans mesure de puissance réelle.
- Heure de départ indicative en Europe/Paris. Elle ne coupe pas la recharge F20.
- Semaine type, du lundi au dimanche. Le préremplissage bureau 8 h–18 h en semaine et domicile le week-end est un **exemple à confirmer**, pas une information sur l'utilisateur.

Les modifications restent dans le brouillon d'écran tant que « Enregistrer le profil » n'a pas réussi. La capacité, les nombres, les horaires et les exceptions sont validés ensemble. Le brouillon survit à une rotation d'écran ; le profil enregistré survit à l'arrêt de l'application. Revenir à la démonstration du relais ne supprime pas ce profil indépendant.

## Semaine type et exceptions

Bureau et absence personnalisée retirent la période départ → retour de la disponibilité. Un retour avant le départ désigne le lendemain. Télétravail, week-end à domicile et domicile supposent une présence toute la journée. **Vacances / absent** retire la journée entière ; pour des congés chez soi, choisir domicile.

Une exception possède une date de début et de fin **incluses** et remplace la semaine type sur cette période. Les chevauchements sont refusés. Une absence nocturne commencée la veille reste prise en compte jusqu'à son retour, même si le profil du nouveau jour est différent. Les absences sont réunies avant de construire les fenêtres disponibles, pour ne pas compter deux fois un intervalle.

Le calendrier est interprété en Europe/Paris puis converti en UTC. Lors d'une heure répétée à l'automne, le début d'absence prend la première occurrence et la fin la dernière : la disponibilité est conservatrice. Une heure inexistante au printemps est avancée par les règles de zone Java ; une absence devenue vide est refusée et demande une exception adaptée. Les journées complètes ont leur durée réelle de 23 ou 25 heures.

Ce calendrier exprime des **hypothèses de présence**, pas une détection de branchement ou une géolocalisation. Aucun accès au calendrier Android ni permission de localisation n'est utilisé.

## Plan personnel

Accueil → **Mon plan personnel** : le profil doit avoir été enregistré. Le niveau actuel de batterie est saisi et confirmé par l'utilisateur, jamais copié depuis le véhicule fictif. Le branchement actuel au domicile doit être confirmé ; une programmation manuelle active bloque une nouvelle proposition.

Le moteur utilise un horizon de 48 heures à partir du calcul, la semaine type et les exceptions, les couleurs récupérées et les prix datés. Il compare le plan économique à une charge continue dans la première fenêtre connectée. Les données futures inconnues restent signalées. Un changement d'entrée, de profil ou de cache invalide le résultat affiché ; un calcul vieux de plus de cinq minutes est marqué ancien. Le retour de l'application au premier plan met à jour l'horloge.

Les trajets ne consomment pas d'énergie dans ce modèle : **recalculer au retour**. Une disponibilité future suppose de rebrancher le véhicule. Aucun plan n'est appliqué, aucune programmation Tesla n'est déclarée compatible sans test réel.

Le simulateur de démonstration reste accessible séparément et continue d'utiliser uniquement ses tarifs fictifs, sans modifier le profil.

## Vérifications

Tests JVM : contrat HTTP du fournisseur, tarifs invalides/expirés, erreurs partielles, cache au changement de date, conversion du calendrier, exceptions, nuits et changements d'heure, sérialisation. Tests instrumentés : saisie/enregistrement du profil, relecture DataStore et parcours Compose. Un test réseau Android supplémentaire est **explicitement activable** avec l'argument d'instrumentation `liveTempo=true` et la classe `org.teslatempo.LiveTempoTest` ; il dépend de la disponibilité du service externe.

Les tests utilisent exclusivement des profils synthétiques et couvrent la persistance du profil, les exceptions, le cache et le calcul hors connexion.

La CI exécute les tests JVM simulés et lint, sans appeler le service Tempo. Les tests de l'interface ouvrent l'application et peuvent déclencher sa synchronisation normale ; ils ne nécessitent pas une réponse réseau pour leurs assertions.

Restent hors périmètre : modification manuelle des grilles tarifaires, import calendrier Android, notifications/WorkManager, historique des plans, consommation des trajets et commandes Tesla. Les prix publiés sont consultables et actualisables ; si aucun prix valide n'est disponible, le calcul échoue explicitement.
