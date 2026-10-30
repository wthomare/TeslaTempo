# Moteur Tempo/F20 — simulation 0.2.0

Depuis la version 0.3.0, un [plan personnel avec données Tempo publiées et calendrier sauvegardé](tempo-personal.md) complète la démonstration décrite ici.

Le moteur Kotlin pur dans `apps/android/app/src/main/java/org/teslatempo/planner/` calcule des propositions. Il n'envoie aucune commande au relais ou à Tesla et ne considère jamais une programmation comme confirmée. Le simulateur Android est indépendant de l'état du véhicule affiché sur l'accueil.

## Entrées et règles

- Batterie initiale, cible (80 % par défaut), capacité utile estimée, rendement réseau → batterie.
- Monophasé 230 V, courant entre 1 et **10 A**. La puissance réseau mesurée facultative peut diminuer l'estimation, jamais dépasser le plafond du courant. Une puissance nulle/invalide est rejetée : le véhicule débranché se représente par une absence de disponibilité.
- Début, horizon explicite (maximum 14 jours), départ **indicatif**, fenêtres de disponibilité au domicile. Le départ ne tronque pas ces fenêtres. Une absence réelle doit au contraire y être inscrite.
- Indicateurs de présence au domicile et de programmation manuelle : absence, indisponibilité et conflit manuel ne génèrent aucun créneau. Aucune programmation existante n'est écrasée.
- Couleurs indexées par jour Tempo et tarifs versionnés avec six prix TTC, provenance et dates de validité. Une version absente/expirée, un chevauchement de versions ou de disponibilités bloque le calcul.

Énergie réseau nécessaire = `capacité × max(cible − SoC, 0) / 100 / rendement`. Le modèle suppose une capacité, une puissance et un rendement constants ; il n'inclut ni consommation en trajet, ni auxiliaires, ni courbe de fin de charge. Ce sont des estimations, pas des mesures facturées.

## Temps et tarifs

Le moteur travaille en instants UTC et découpe aux frontières locales `Europe/Paris` : minuit, 6 h, 22 h, ainsi qu'aux bornes des disponibilités. La **couleur change à 6 h**, les heures creuses vont de 22 h à 6 h. Avant 6 h, la couleur est donc celle du jour précédent. Les durées réelles tiennent compte des changements d'heure : une nuit complète peut durer 7 ou 9 heures.

Les dates de validité des grilles sont ici des **dates civiles**, borne de début incluse et borne de fin exclue : une nouvelle grille s'applique à minuit sans modifier la couleur Tempo. L'import futur devra respecter les dates d'effet publiées.

Si une couleur manque, elle reste `null`/« inconnue ». Le prix retenu est le maximum des trois couleurs pour la période HC ou HP concernée. L'estimation est marquée conservatrice ; aucune publication EDF n'est inventée.

Règles horaires vérifiées le 29 octobre 2026 sur [la présentation officielle EDF Tempo](https://particulier.edf.fr/fr/accueil/gestion-contrat/options/tempo/details.html). **Aucun prix ni calendrier réel n'est intégré à cette version.**

## Plan économique et secours

Le plan économique remplit les créneaux disponibles par prix croissant, puis par date pour départager les égalités. À puissance constante et prix linéaire, ce choix minimise le coût pour l'énergie demandée sur l'horizon fourni. La dernière portion est tronquée à l'énergie nécessaire ; sa borne temporelle est arrondie à la nanoseconde supérieure. Le coût utilise l'énergie allouée, avec arrondi uniquement à l'affichage.

La cible est prioritaire sur la fin des HC et sur l'heure de départ indicative : du complément HP, même rouge, est permis. Un horizon ou une disponibilité insuffisants produit un plan partiel, un SoC estimé et une énergie manquante ; aucune heure d'atteinte n'est annoncée.

Le secours propose une charge continue à partir de la première disponibilité jusqu'à la cible ou la fin de la **première fenêtre connectée**. Il ne promet aucune reprise après un débranchement. Les fenêtres contiguës sont compatibles avec cette continuité. Son coût est calculé avec les mêmes tarifs et hypothèses. Une comparaison de coût entre plans livrant des énergies différentes est signalée comme non équivalente.

Le résultat distingue le coût HP et le **surcoût HP par rapport aux HC de la même couleur et version**. Cette référence sert à expliquer le complément, pas à promettre une économie réalisable. Pour une couleur inconnue, les références sont également les bornes hautes respectives.

`requiresMultipleWindows` signale les interruptions du plan économique. **Cela ne valide aucune capacité de programmation Tesla**, même pour un seul créneau. Le résultat reste un brouillon, `executionConfirmed = false`. La mise en œuvre du secours, la limite à 80 %, le courant et la compatibilité avec les programmes manuels devront être confirmés dans Tesla. Pas de bouton « Appliquer » dans cette version.

## Démonstration Android

Accueil → **Simuler un plan F20**. Modifier batterie, capacité et courant ; choisir présence jusqu'à 18 h, absence réelle dès 8 h ou couleur du lendemain inconnue. Paramètres sauvegardés uniquement dans l'état d'écran, pas d'historique persistant.

Le scénario utilise une nuit fictive bleue, un lendemain fictif rouge (ou inconnu), 22 h → 18 h le lendemain, départ indicatif 8 h, rendement 90 %. La grille `demo-v1`, affichée avec ses dates, contient des **prix inventés pour les tests**, pas des tarifs EDF :

| Couleur | HC €/kWh | HP €/kWh |
| --- | ---: | ---: |
| Bleu | 0,10 | 0,15 |
| Blanc | 0,12 | 0,20 |
| Rouge | 0,14 | 0,70 |

Exemple hors changement d'heure : 20 % → 80 %, batterie de 60 kWh, 10 A, rendement 90 %. Besoin de 40 kWh réseau. Avec disponibilité jusqu'à 18 h : 18,4 kWh en HC puis 21,6 kWh en HP, coût fictif de **16,96 €**, cible vers 15 h 23. Avec absence à 8 h : seulement 23 kWh, SoC estimé **54,5 %**, besoin restant **17 kWh**.

## Validation et limites

Les tests JVM couvrent les six tarifs, minuit/6 h/22 h, changements d'heure, versions, inconnues, coût HP rouge, départ vs absence, énergie conservée, puissance réduite, entrées invalides, indisponibilité et secours. Le test Compose couvre la simulation, l'absence et le refus de 11 A.

La source Tempo et les profils de mobilité sont intégrés en 0.3.0. Restent à intégrer : saisie/import manuel des tarifs, consommation des trajets, historique complet des sessions, détection réelle du branchement/domicile et commandes Tesla avec confirmation. Ne pas utiliser la simulation pour piloter une recharge réelle.
