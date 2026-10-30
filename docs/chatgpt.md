# ChatGPT sans clé API

## Assistant intégré — Android 0.6.0 / relais 0.2.0

L'assistant explique une simulation archivée. Il ne dispose d'aucun outil Tesla,
ne modifie pas les calculs et ne programme pas la voiture. La connexion utilise
le parcours officiel [Sign in with ChatGPT](https://developers.openai.com/siwc/token-sharing-open-source)
pour les comptes Plus et Pro éligibles, sans clé API ni secret client OpenAI.
Le code est couvert par des tests simulés ; un consentement et un appel réels
restent nécessaires pour valider l'accès avec chaque compte.

### 1. Connexion sur le poste où fonctionne le navigateur

Depuis la racine du dépôt, dans un terminal local Linux :

```bash
python3 -m venv .venv
.venv/bin/pip install -r services/relay/requirements.txt
.venv/bin/python scripts/chatgptctl.py login
```

Le navigateur ouvre **Continue with ChatGPT**. Vérifier le nom TeslaTempo, le compte,
l'espace de travail et les autorisations, puis accepter uniquement les options
souhaitées. Vérifier les éventuels crédits supplémentaires et les limites dans
[les réglages d'utilisation ChatGPT](https://chatgpt.com/settings/usage).
Le logiciel n'effectue aucun basculement automatique vers une clé API payante.

Le retour doit atteindre le poste sur `127.0.0.1`, port choisi automatiquement,
chemin `/auth/callback`. Ne pas exécuter `login` par SSH sur le NAS : le navigateur
et le serveur de retour doivent être sur le même poste. Le parcours expire après
dix minutes. Ne pas partager l'URL d'autorisation ou de retour.

Les données sont chiffrées dans `~/.config/teslatempo/chatgpt/chatgpt.enc` ; la clé
locale `local.key` et les fichiers sont privés. Conserver ces deux fichiers hors
Git et des dossiers partagés. Aucun identifiant d'un autre logiciel n'est lu.

```bash
.venv/bin/python scripts/chatgptctl.py status
.venv/bin/python scripts/chatgptctl.py models
```

Le statut n'affiche pas de jeton. `plan_enabled: true` indique les droits accordés ;
seul un appel réussi confirme leur disponibilité actuelle. Pour réautoriser une
inscription existante, reprendre son identifiant affiché par `status` :

```bash
.venv/bin/python scripts/chatgptctl.py login --account oaiapp_IDENTIFIANT
```

`login` sans `--account` crée une nouvelle inscription. `select --account ...`
change l'inscription active sans mélanger ses identifiants avec ceux d'une autre.

### 2. Transfert privé vers son relais

Installer d'abord une image du relais **0.2.0 ou ultérieure**. Le répertoire de données
persistantes contient un sous-dossier `chatgpt/`, chiffré avec la clé du relais.
Le relais crée son propre identifiant d'hôte avant l'import.

Exporter une seule inscription dans un fichier de transfert privé :

```bash
.venv/bin/python scripts/chatgptctl.py export --file /tmp/teslatempo-chatgpt-transfer.json
```

Ce fichier contient des secrets en clair, avec permissions `600`. Le transférer
par SSH/SCP vers un emplacement privé du NAS, puis le placer temporairement dans
le sous-dossier `chatgpt/` du volume de données. Lui donner le propriétaire du
processus relais et conserver le mode `600`. Ne jamais le joindre à un message,
le placer dans le YAML ou l'ajouter à Git.

Dans le conteneur relais, en adaptant son nom :

```bash
docker exec NOM_DU_CONTENEUR python -m relay.chatgpt_cli import \
  --directory /data/chatgpt \
  --key-file /run/secrets/token_encryption_key \
  --file /data/chatgpt/transfer.json
```

L'import préserve l'identifiant d'hôte du NAS. Supprimer ensuite les copies de
transfert sur le NAS et le poste. Le NAS devient responsable du renouvellement :
ne pas appeler `models` ou utiliser cette même session en parallèle sur le poste.
Ne pas déconnecter la copie locale pour la nettoyer : cela révoquerait la session
transférée. Les inscriptions restent séparées, même si elles concernent le même
compte dans plusieurs espaces de travail.

Le [guide officiel des VM auto-hébergées](https://developers.openai.com/siwc/token-sharing-open-source/self-hosted-vms)
précise les limites d'attribution et de révocation propres aux sessions transférées.

### 3. Utiliser Android

Configurer l'URL HTTPS et le jeton habituel du relais dans les réglages Android.
Dans **Historique des simulations**, consulter une simulation, puis :

1. **Vérifier la connexion ChatGPT** ;
2. choisir un modèle proposé par le compte ;
3. **Relire avant envoi**, vérifier le résumé et le modèle ;
4. **Envoyer** pour demander l'explication.

L'envoi consomme les quotas du compte connecté au relais. Le résumé contient les
hypothèses de batterie et les agrégats de coûts/énergie, sans dates, activités,
identifiants de véhicule, sources textuelles libres ou secrets. L'aperçu montre
le texte fourni comme données ; le relais ajoute une consigne d'explication sans
outil. Les réponses ne sont pas enregistrées dans l'historique.

Les requêtes utilisent `store: false` et `stream: true` ; cela ne constitue pas une
promesse d'absence de toute rétention chez le fournisseur. Le relais n'enregistre
ni le résumé ni la réponse. Il attend la fin complète avant d'afficher un résultat.
Limites applicatives : une génération simultanée, cinq tentatives par minute,
6 000 caractères de résumé, réponse affichée limitée à 16 000 caractères.

**Déconnecter ChatGPT du relais** retire les jetons actifs et tente leur révocation
chez OpenAI. Si cette révocation n'est pas confirmée, retirer aussi l'accès dans
les réglages ChatGPT. La connexion Tesla reste indépendante.

### Erreurs et limites

- Relais ancien : mettre à jour l'image, les routes ChatGPT n'existent pas en V1.
- Connexion ou consentement manquant : refaire `login --account ...` puis transférer
  la nouvelle session. Ne pas copier les identifiants de Codex ou d'un navigateur.
- Quota ou requête en cours : attendre ; aucune relance automatique payante.
- Réponse interrompue : aucun texte partiel n'est présenté comme une réponse complète.
- Modèle retiré : actualiser le catalogue et choisir à nouveau.

## Partage manuel toujours disponible

**Préparer pour ChatGPT** affiche un résumé à copier ou à partager via Android.
Ce mode ne nécessite pas la connexion intégrée ni la mise à jour du relais.
**Annuler** ferme sans transmettre. Copier utilise le presse-papiers ; partager
confie le texte à l'application choisie. La réponse reste alors dans ChatGPT.

Sources : [connexion et validation OIDC](https://developers.openai.com/siwc/token-sharing-open-source/sign-in),
[sessions et renouvellement](https://developers.openai.com/siwc/token-sharing-open-source/profiles-and-sessions),
[modèles et réponses](https://developers.openai.com/siwc/token-sharing-open-source/models-and-inference).
