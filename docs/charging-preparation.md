# Préparation des commandes — socle hors ligne

Le module Kotlin `execution/Preparation.kt` prépare les contrôles préalables et
la confirmation des futurs programmes. Il n'est pas encore relié à un transport ou à des commandes Tesla. Un aperçu
distinct est disponible dans Android, sans bouton d'application. Le relais
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

## Aperçu natif dans Android 0.7.0

Depuis un plan personnel ou une démonstration, **Préparer un programme Tesla**
affiche séparément le plan économique et le secours continu. Le début est arrondi
à la minute, avec deux minutes de préparation, puis les coûts sont recalculés pour
une charge continue. Il n'y a pas d'heure de fin forcée : la limite de batterie
arrête la recharge, sans coupure arbitraire à 6 h ou au départ indicatif.

Les plans à fenêtres multiples, les disponibilités qui supposent un nouveau
branchement, les calculs périmés, les cibles incomplètes et les changements d'heure
sont refusés pour ce parcours. Une couleur inconnue reste un avertissement avec
estimation conservatrice. Un démarrage à plus de 18 heures est également exclu
pour éviter de supposer un comportement non vérifié de la programmation ponctuelle.
L'aperçu n'est pas une preuve de capacité du véhicule ni un consentement à agir.

Ces choix s'appuient sur le [manuel Model Y](https://www.tesla.com/ownersmanual/modely/en_us/GUID-76995CEC-6402-4BFF-99FA-CEFA36E64A19.html).
Les champs de programmation sont décrits dans le [SDK officiel figé](https://github.com/teslamotors/vehicle-command/blob/d6239bf377b8a5859e8f5c9d64409df2a057934a/pkg/proxy/command.go).
La représentation et le comportement doivent encore être vérifiés sur le firmware
et la région du véhicule utilisé.

## Proxy de signature préparé

Le [composant facultatif](../services/command-proxy/README.md) construit le proxy
Tesla officiel depuis une source vérifiée. Il écoute uniquement sur la boucle
locale du relais, utilise une clé TLS distincte et n'est pas démarré par défaut.
Son test de démarrage utilise des clés fictives sans réseau externe. L'acceptation
d'une commande signée et l'adaptateur de commandes du relais restent à développer
ou valider avant toute activation.
