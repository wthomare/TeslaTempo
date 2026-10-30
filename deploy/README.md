# Configurations de déploiement

Ce dossier contient les exemples complémentaires à [compose.yaml](../compose.yaml), conservé à la racine pour démarrer facilement l'instance.

- [truenas-compose.example.yaml](truenas-compose.example.yaml) : Custom App TrueNAS, chemins et UID/GID à adapter.
- [Manuel d’installation TrueNAS](../docs/installation-truenas.md) : accès depuis un nouveau poste, installation et reprise.
- [truenas-https-compose.example.yaml](truenas-https-compose.example.yaml) et [Caddyfile.truenas.example](Caddyfile.truenas.example) : relais et proxy HTTPS dans la même Custom App ; voir le [guide HTTPS](../docs/https-truenas.md).
- [Caddyfile.example](Caddyfile.example) : exemple de terminaison HTTPS à adapter au domaine et à l'adresse du relais.
- [Guide du relais V1](../docs/relay-v1.md) : déploiement local, TrueNAS SCALE et Freebox.
- [Contrat d'auto-hébergement](../docs/self-hosting.md) : responsabilités de l'utilisateur.

Le Dockerfile appartient au service : [services/relay/Dockerfile](../services/relay/Dockerfile). Aucun secret ni configuration personnelle ne doit être ajouté ici.
