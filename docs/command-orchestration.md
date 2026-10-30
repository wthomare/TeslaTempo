# Chaîne de commandes avant essais sur véhicule

Le module `services/relay/relay/commands.py` est une bibliothèque testée hors
véhicule. **Aucune route d'écriture réelle n'est activée dans FastAPI ou Android.**
L'aperçu Android n'est pas encore relié à cette bibliothèque. Une mise à jour
ne change ni les scopes OAuth Tesla, ni les droits, ni les programmes du véhicule.

## Contrat et ordre

1. Préparer un programme ponctuel : VIN, début à la minute en Europe/Paris,
   limite jusqu'à 80 %, intensité jusqu'à 10 A, position et identifiant explicite.
2. Exiger des preuves issues d'un lecteur de confiance : véhicule en ligne,
   branché, stationné au domicile, sans charge active ni programmes existants,
   clé et capacité de programmation vérifiées, batterie connue, données < 2 min.
   Une affirmation du téléphone ne constitue pas une preuve véhicule.
3. Enregistrer une confirmation chiffrée, liée à l'empreinte du programme exact,
   valable au maximum 90 s. Le début doit laisser au moins deux minutes et rester
   à moins de 18 h. Les changements d'heure sont refusés.
4. Consommer atomiquement la confirmation avant tout envoi. Refaire une lecture
   fraîche ; rejeter une modification des programmes ou de la batterie (> 1 point).
5. Envoyer séquentiellement `set_charging_amps`, `set_charge_limit`, puis
   `add_charge_schedule` ponctuel, sans heure de fin. Aucun démarrage explicite,
   réveil, effacement de programme, outil ChatGPT ou nouvelle tentative automatique.
6. Relire les réglages et le programme : identité, fraîcheur postérieure à l'envoi,
   intensité, limite, identifiant, jour, minute, position et exécution unique.
   Seule une correspondance complète donne `VERIFIED`.

Le lecteur Fleet réel doit encore normaliser les réponses observées vers
`Evidence`/`Snapshot`. Il devra vérifier les droits et l'appartenance du véhicule,
contourner le cache de lecture ordinaire, conserver les champs manquants comme
inconnus et vérifier les programmes natifs/anciens pertinents. Il n'est pas remplacé
par un parseur qui devine les réponses absentes. Son branchement et le parcours
Android de confirmation attendent cette validation.

## Résultats et incidents

| État | Sens |
| --- | --- |
| PREPARED | Confirmation non consommée, aucune commande |
| NOT_SENT | Refus avant envoi ou première commande explicitement refusée |
| PARTIAL | Une ou plusieurs commandes acquittées, puis refus explicite |
| UNKNOWN | Réponse perdue, relecture différente/incomplète ou processus interrompu |
| VERIFIED | Toutes les commandes acquittées et réglages relus conformes |

Un ACK n'est pas une preuve d'application. Après un résultat partiel/inconnu,
aucun rollback automatique ni nouvelle préparation pour le même véhicule.
La réconciliation réelle devra être faite avec une nouvelle lecture fiable ;
aucun bouton de déblocage aveugle n'est fourni. Les écritures sont séquentielles,
non atomiques : l'intensité ou la limite peuvent avoir changé sans programme ajouté.
Les effets de leur ordre doivent être vérifiés sur le véhicule avant activation.
Un autre client Tesla peut aussi modifier un réglage après une lecture.

Le journal SQLite est chiffré avec Fernet et privé (600), borné à 1 000 tentatives.
Il refuse de nouvelles entrées plutôt qu'effacer une opération non résolue.
Un redémarrage après consommation interdit de rejouer la confirmation. Le module
n'est pas instancié dans le relais de production tant que les commandes sont désactivées.

## Transport signé

`SignedTransport` utilise exclusivement `https://localhost:4443`, fait confiance
au certificat TLS explicitement fourni, ignore les proxys d'environnement et
refuse redirections, commandes hors liste et paramètres hors limites. La réponse
est bornée à 64 Kio. La clé privée Tesla demeure dans le proxy officiel.

Voir [le proxy](../services/command-proxy/README.md). Les schémas d'envoi proviennent
du [SDK Tesla épinglé](https://github.com/teslamotors/vehicle-command/blob/d6239bf377b8a5859e8f5c9d64409df2a057934a/pkg/proxy/command.go)
et des [endpoints officiels](https://developer.tesla.com/docs/fleet-api/endpoints/vehicle-commands).

## Répéter sans voiture ni compte

```bash
.venv/bin/python scripts/rehearse-commands.py
PYTHONPATH=services/relay .venv/bin/pytest -q services/relay/tests/test_commands.py
```

Les scénarios utilisent des clés éphémères, un stockage temporaire et des données
fictives : réussite relue, sommeil, débranchement, conflit, refus partiel, timeout
et relecture différente. Les tests couvrent aussi concurrence, reprise après
interruption, expiration, mauvais véhicule, limites et refus des payloads dangereux.
Ils ne prouvent pas qu'un véhicule réel acceptera ces commandes.
