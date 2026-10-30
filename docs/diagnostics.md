# Diagnostics Android et relais

Disponible avec Android 0.9.0 et le relais 0.5.0. Le relais reste utilisable sans
Loki ni Grafana. Ce dispositif sert au dépannage, pas à un audit exhaustif ni à
la preuve qu'une commande véhicule a été appliquée.

## Ce qui est enregistré

Chaque opération HTTP Android reçoit un UUID aléatoire `X-Request-ID`, repris par
le relais et renvoyé dans sa réponse. Un rapport Android confirmé reçoit aussi un
UUID `report_id`. Rechercher l'un de ces identifiants permet de rapprocher les
événements Android et relais, sans identifiant permanent du téléphone.

Le schéma contient exclusivement : identifiants aléatoires, date technique UTC,
source (`android`, `relay`, `collector`), version, opération et résultat parmi
une liste fermée, code HTTP, durée bornée, catégorie d'erreur parmi une liste
fermée, fichier source connu et numéro de ligne lorsque disponibles.

Aucun champ libre : ni message d'exception, variables locales, URL, paramètres,
headers, jeton, code OAuth, VIN, GPS, calendrier, prompt, réponse ChatGPT ou
réponse brute Tesla. Le serveur refuse un rapport contenant des champs inconnus.
Les requêtes non authentifiées ne remplissent pas le journal. Les logs HTTP
bruts et les logs Docker génériques ne sont pas aspirés dans Loki.

**Les horaires et les opérations restent des informations privées** : les
rapports ne sont pas anonymes. Ne pas les joindre automatiquement à une issue
publique ; vérifier le fichier exporté avant de le partager.

## Depuis Android

Dans **Réglages → Diagnostics privés** :

1. Consulter les derniers événements et leurs références.
2. Choisir **Envoyer un diagnostic** : l'aperçu montre les 100 derniers événements
   au maximum. Confirmer envoie ce contenu vers le relais déjà enregistré.
3. Si le réseau est indisponible, le rapport confirmé reste en attente. Il peut
   être réessayé explicitement ou à la prochaine ouverture de l'application,
   au maximum une tentative automatique par minute. Aucun nouvel événement n'est
   ajouté à ce rapport après consentement.
4. Un changement d'adresse ou de jeton annule cette attente : elle ne doit pas
   partir vers une autre installation. Une nouvelle confirmation remplace l'attente
   précédente ; une requête déjà en cours peut toutefois aboutir.
5. **Exporter le diagnostic JSON** fonctionne sans NAS. Le sélecteur Android
   permet de choisir une destination privée. Le fichier contient jusqu'à 500 événements.
6. **Effacer les diagnostics locaux** efface le tampon et l'attente ; cela n'efface
   pas les rapports déjà reçus sur le NAS.

Le tampon local est privé, exclu des sauvegardes Android, borné à 500 événements
et sept jours. Les exceptions non interceptées sont enregistrées au mieux sans
leur texte. Un arrêt forcé, un manque de mémoire ou un blocage Android peuvent ne
pas laisser d'événement. Le redémarrage du processus et les erreurs réseau restent
utiles pour situer l'incident.

## Architecture NAS optionnelle

```text
Android -- HTTPS + jeton + consentement --> relais
                                           |
                                  snapshot JSON filtré
                                           |
                          collecteur indépendant, lecture seule
                             |                        |
                         Loki privé           contrôle /healthz
                             |
                         Grafana privé <-- tunnel SSH <-- PC
```

Le collecteur n'a ni socket Docker, ni clé Tesla, ni jeton du relais, ni accès au
volume de données complet. Il voit uniquement `data/diagnostics/events.json` en
lecture seule et appelle `/healthz`. Il signale les transitions disponible /
indisponible, même lorsque le relais est arrêté. Les événements de démarrage et
arrêt normal viennent aussi du relais. Une erreur de configuration avant son
initialisation sera visible comme indisponibilité, pas comme une stack trace brute.

Le relais écrit un snapshot atomique toutes les deux secondes, hors des requêtes.
Une panne du disque de diagnostic, de Loki ou du collecteur ne doit pas empêcher
les lectures et opérations du relais. Le tampon est borné : en cas de panne longue,
les événements les plus anciens sont remplacés. Une panne brutale peut perdre les
dernières secondes ; une réponse réseau perdue peut produire des doublons côté
Loki. Les identifiants permettent de les reconnaître.

Loki conserve nominalement sept jours **après ingestion**, avec le délai de
suppression du compactor (deux heures configurées). La date de l'événement Android
reste dans `at` ; le temps indexé dans Loki est celui de réception par le collecteur.
Un rapport différé apparaît donc à sa réception. Le champ `at` conserve son origine.

## Installation générique

Prérequis : relais 0.5.0 démarré, Docker Compose, dossier privé de données et réseau
Docker du relais identifié. Depuis la racine du dépôt :

```bash
python3 scripts/init-local.py
python3 scripts/init-observability.py --directory "$HOME/.local/share/teslatempo-logs"
docker build -t teslatempo-diagnostics:0.1.0 -f services/diagnostics/Dockerfile .
export OBSERVABILITY_HOME="$HOME/.local/share/teslatempo-logs"
export RELAY_DIAGNOSTICS_DIR="$PWD/data/diagnostics"
export RELAY_NETWORK=teslatempo_default
export OBS_UID="$(id -u)" OBS_GID="$(id -g)"
docker compose -f deploy/observability/compose.yaml config --quiet
docker compose -f deploy/observability/compose.yaml up -d
```

Les images Loki/Grafana sont épinglées par digest. Le contexte de construction du
collecteur exclut les secrets, fichiers privés et builds Android. Les UID/GID du
collecteur et du relais doivent permettre la lecture du fichier privé (600).
Sur TrueNAS, remplacer les chemins par des datasets et utiliser l'UID du relais ;
le réseau est souvent préfixé `ix-`. Installer le Compose comme application
personnalisée distincte, sans modifier Nextcloud, WireGuard ou les redirections WAN.

Configurer des quotas **séparés** des données du relais : par exemple Loki 2 Gio,
Grafana 512 Mio, collecteur 64 Mio, total parent 3 Gio. La rétention temporelle ne
constitue pas à elle seule une limite de disque. Les limites mémoire du Compose
sont 512 Mio, 384 Mio et 96 Mio respectivement. Les logs Docker sont aussi limités
par rotation. Si un quota est atteint, l'historique peut devenir incomplet ; le
relais principal doit continuer à fonctionner.

## Accès depuis le PC

Grafana écoute uniquement sur la boucle locale du NAS, port 13000. Loki n'a aucun
port publié. Aucune redirection Freebox n'est nécessaire.

```bash
bash scripts/logs-tunnel.sh admin@ADRESSE_LAN_DU_NAS -i ~/.ssh/cle_nas
```

Garder le terminal ouvert, puis consulter `http://localhost:13000`. Le trajet
PC–NAS est chiffré par SSH. Il fonctionne sur le réseau local ou via le VPN quand
SSH vers le NAS est accessible. Le mot de passe Grafana initial est dans
`OBSERVABILITY_HOME/secrets/grafana_admin_password`, fichier privé créé une seule
fois. Utilisateur initial : `admin`. Ne pas copier ce secret dans Git ou une issue.
Changer le fichier après initialisation ne change pas le compte déjà enregistré.

Ouvrir **Dashboards → TeslaTempo → TeslaTempo — diagnostics**, puis coller une
référence dans **Identifiant requête ou rapport**. Les autres panneaux montrent
les erreurs et les transitions de disponibilité. Grafana sert aussi les requêtes
Loki via sa source de données authentifiée. Les proxys d'environnement sont
neutralisés pour les communications internes de cette stack.

## Export sans Grafana

```bash
python3 scripts/diagnosticsctl.py --url https://relais.exemple.fr \
  --token-file secrets/relay_api_token --output private/diagnostic.json
```

Le fichier de sortie est créé en 600, sans écrasement ; le jeton ne passe jamais
sur la ligne de commande. L'endpoint protégé `/v1/diagnostics/export` expose les
500 événements en mémoire au maximum et le nombre d'évictions depuis le démarrage.

## Vérifications reproductibles

```bash
PYTHONPATH=services/relay .venv/bin/pytest -q services/relay/tests/test_diagnostics.py
.venv/bin/python scripts/check-observability.py
```

Le test Docker crée une instance fictive temporaire, vérifie corrélation, absence
de données sensibles, authentification Grafana, dashboard, panne de Loki et arrêt
du relais. Il nécessite les images indiquées dans le Compose, l'image relais
0.5.0, le collecteur et le port local 13000 libre. Il nettoie ses conteneurs et
volumes temporaires. Il n'utilise aucun compte Tesla/ChatGPT.

Sources : [Loki](https://grafana.com/docs/loki/latest/get-started/overview/),
[rétention](https://grafana.com/docs/loki/latest/operations/storage/retention/),
[provisionnement Grafana](https://grafana.com/docs/grafana/latest/administration/provisioning/).
