# Utiliser ChatGPT sans clé API

## Disponible dans Android 0.5.0 : partage volontaire

Dans **Historique des simulations**, consulter une simulation enregistrée, puis
choisir **Préparer pour ChatGPT**. Relire l'intégralité du texte avant de choisir :

- **Copier** : coller ensuite le résumé dans une conversation ChatGPT ;
- **Partager…** : sélectionner ChatGPT si l'application est proposée par Android ;
- **Annuler** : fermer sans transmettre.

Aucune clé API ni configuration du relais n'est nécessaire. Le résumé contient
les hypothèses de batterie, les énergies et coûts estimés des deux plans, leur
origine personnelle ou fictive et la présence de couleurs inconnues. Il exclut les
dates, horaires, activités, sources textuelles libres, identifiants et secrets.
L'aperçu montre exactement le texte exporté. Copier place ce texte dans le
presse-papiers Android ; partager le confie à l'application choisie.

Il ne s'agit pas encore d'un assistant intégré : la réponse reste dans ChatGPT,
sans retour automatique vers TeslaTempo et sans commande véhicule. Les agrégats
ne permettent pas de recalculer les créneaux. Les estimations archivées ne sont
ni des dépenses mesurées ni des économies réalisées.

## Connexion intégrée prévue : Sign in with ChatGPT

La [documentation officielle OpenAI](https://developers.openai.com/siwc/quickstart)
décrit un parcours pour les applications open source permettant aux comptes
Plus et Pro éligibles d'utiliser leur abonnement sans clé API ni secret client.
L'accès effectif et les quotas doivent être vérifiés lors du consentement ; une
connexion d'identité seule n'autorise pas les requêtes d'inférence. Cette connexion
n'est pas encore implémentée dans TeslaTempo.

Pour un relais auto-hébergé, le parcours documenté impose une authentification
locale dans le navigateur avec retour sur une adresse de boucle locale, puis un
transfert protégé des seuls identifiants de cette application vers l'hôte.
Le domaine HTTPS du relais Tesla ne remplace pas ce retour local.
Voir [le guide OpenAI pour les VM auto-hébergées](https://developers.openai.com/siwc/token-sharing-open-source/self-hosted-vms).

L'implémentation devra vérifier OAuth/OIDC et PKCE, protéger et renouveler les
jetons, permettre la déconnexion et respecter les droits effectivement accordés.
Elle ne doit pas récupérer les identifiants d'une autre application installée.
Le moteur de planification restera l'autorité pour les calculs ; les explications
ne déclencheront pas de commande Tesla. Aucun basculement vers une API facturée
séparément ne sera automatique.
