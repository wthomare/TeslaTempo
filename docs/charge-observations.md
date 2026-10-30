# Relevés de recharge

Depuis le relais 0.3.0 et Android 0.7.0, les lectures réelles de l'état de charge
peuvent être conservées comme instantanés. Cette fonction complète l'historique
local des simulations ; elle ne constitue pas encore un historique exhaustif de
sessions domestiques ou de factures Tesla.

## Utilisation

Depuis l'accueil Android, ouvrir **Relevés de recharge**, puis **Charger les relevés**.
Le véhicule est celui sélectionné dans le tableau de bord. Le chargement est explicite,
sans polling automatique. La consultation peut vérifier les droits actuels sur le
véhicule via la liste Tesla, mise en cache par le relais.

La conservation se fait pendant les appels existants à `charge-state` : aucun
appel supplémentaire à `vehicle_data` et aucun réveil ne sont ajoutés pour collecter
un relevé. Une lecture servie depuis le cache ne crée pas de doublon si son
horodatage est identique. Sans voiture accessible, le journal reste vide.

Les relevés affichent :

- l'heure rapportée par le véhicule, présentée dans le fuseau Europe/Paris ;
- l'état de charge, la batterie et le courant lorsqu'ils sont disponibles ;
- le compteur `charge_energy_added` de la session signalée par Tesla ;
- un coût **inconnu**, faute de facture ou de mesure tarifaire complète.

Un compteur répété n'est pas une nouvelle consommation. Un compteur qui revient
à zéro n'est pas une consommation négative. Les relevés ne sont donc jamais
additionnés et aucune économie réalisée n'est annoncée. Une donnée absente ou
invalide reste inconnue. Un horodatage absent, trop ancien (plus de cinq minutes)
ou trop éloigné dans le futur est exclu de la collecte.

## Stockage et suppression

Les relevés sont chiffrés sur le relais dans `charge-observations.sqlite3`, avec la
clé persistante de l'instance. La base conserve au maximum 300 relevés par véhicule
et 1 000 au total ; les plus anciens sont supprimés automatiquement. Les
identifiants des véhicules sont remplacés dans les index par des empreintes avec
clé. Les coordonnées, VIN et champs libres ne sont pas conservés dans les relevés.

Android n'enregistre pas ces relevés sur disque. **Effacer les relevés de ce véhicule**
demande confirmation et ne supprime ni les simulations locales ni des données chez
Tesla. La suppression concerne le stockage du relais et ne nécessite pas d'appel
Tesla. Les actualisations suivantes peuvent de nouveau enregistrer des relevés.
Aucun relevé n'est transmis à ChatGPT par cette fonction.

## Limites et suite

Des lectures ponctuelles ne permettent pas d'affirmer qu'une session entière a été
observée. Il reste à valider les réponses réelles du véhicule, la collecte de sessions,
les montants facturés accessibles au compte et une éventuelle stratégie de télémétrie
continue avec un budget explicite. Le mode démonstration ne fabrique pas de relevés
présentés comme réels.
