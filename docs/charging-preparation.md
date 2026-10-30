# Préparation des commandes — socle hors ligne

Le module Kotlin `execution/Preparation.kt` prépare les contrôles préalables et
la confirmation des futurs programmes. Il n'est pas encore relié à un écran
d'application de programme, à un transport ni à des commandes Tesla. Le relais
continue d'annoncer `vehicle_commands_enabled: false`.

## Contrôles testables sans voiture

- Calcul et observation du véhicule datant de moins de deux minutes, sans date future.
- Identité du véhicule, batterie cohérente, branchement au domicile et véhicule en ligne.
- Programmations existantes effectivement lues : une information absente est bloquante.
- Signature et capacité de programmation vérifiées ; elles ne sont jamais supposées.
- Courant au plus 10 A et cible au plus 80 %, estimation cohérente et objectif complet.
- Créneaux futurs inclus dans les disponibilités ; un changement d'heure reste
  bloqué jusqu'à validation de sa représentation par l'adaptateur Tesla.

Une empreinte lie la confirmation au véhicule, à l'observation, à la révision des
programmes, aux paramètres physiques, aux créneaux et aux coûts affichés. Toute
modification invalide la confirmation ; elle expire et n'est consommable qu'une
fois. Cette empreinte n'est **pas** une signature ou une autorisation serveur :
le futur relais de commandes devra recalculer et revérifier ces conditions.

Un accusé de réception produit `AWAITING_READBACK`, jamais un succès définitif.
Un timeout produit `UNKNOWN_OUTCOME`. Seule une relecture concordante pourra
produire `APPLIED_CONFIRMED`. Aucun mécanisme de répétition automatique n'est fourni.

## Travail encore nécessaire

Implémenter et tester l'adaptateur des programmes Tesla, notamment les limites de
créneaux, les répétitions, la localisation et les changements d'heure ; intégrer
le proxy de signature officiel ; traiter les écritures partielles et les conflits
entre lecture et écriture. Les preuves de capacité et les relectures ne doivent
pas être de simples indicateurs transmis par le téléphone.

Les essais réels demandent un véhicule accessible, une clé virtuelle appairée et
un programme précis confirmé par l'utilisateur. Les archives de simulation ne
constituent pas des commandes prêtes à exécuter.
