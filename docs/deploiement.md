# Déploiement

## 1. Démonstration universitaire ou exploitation réelle ?

| | Démonstration | Exploitation réelle de fonds collectifs |
|---|---|---|
| Données | Jeu fictif (`docker-compose.demo.yml`) | Données réelles, jamais mélangées à la démonstration |
| Paiements | Simulateur signalé | Wave avec contrat marchand, après essai réel ; encaissements manuels à double contrôle |
| Hébergement | Une petite machine suffit ; une interruption est acceptable | Disponibilité, sauvegardes hors site testées, supervision, contrat d'hébergement, localisation des données |
| Sécurité | HTTPS, secrets propres à l'instance, aucun compte privilégié exposé au public sans protection | En plus : audit de sécurité indépendant, double authentification des rôles financiers, procédure d'incident |
| Juridique et comptable | Aucune obligation particulière | Formalités CDP, validation du plan de comptes par un expert-comptable, décision d'AG sur les règles de cotisation, de pénalité et d'attribution, dispositif réel de garde des fonds |

Une démonstration publique ne doit pas exposer les comptes fictifs à privilèges élevés à n'importe qui : la
partager avec un mot de passe de démonstration communiqué aux seuls évaluateurs, ou la restreindre par IP ou par
authentification au niveau du reverse proxy.

## 2. Ce dont l'application a besoin

- Une base **PostgreSQL 16 persistante**, sauvegardée.
- Un **processus serveur permanent** : les tâches planifiées (génération des échéances à 1 h, pénalités à 1 h 30,
  rappels J-3 à 8 h, envoi des notifications chaque minute, réconciliation des paiements toutes les 5 minutes)
  s'exécutent dans le serveur. Elles sont idempotentes et rattrapent un retard (le rappel J-3 est envoyé au plus
  tard la veille de l'échéance si l'exécution du jour a été manquée), mais un serveur qui dort la plupart du temps
  dégrade fortement le service.
- Un **volume persistant** pour les documents (reçus, PV, rapports, justificatifs).
- Une **adresse HTTPS publique** joignable par Wave pour les webhooks.

## 3. Offres gratuites examinées (vérifiées le 19 octobre 2026)

Les conditions changent souvent : les revérifier avant de choisir.

| Offre | Constat | Conséquence pour CoopHabitat |
|---|---|---|
| Render (gratuit) | Service web mis en veille après 15 min sans trafic, redémarrage d'environ une minute, 750 h d'instance par mois ; base PostgreSQL gratuite de 1 Go **qui expire 30 jours après sa création** (puis 14 jours de grâce) [^render] | Convient à une démonstration courte ; **inadapté** à des données réelles (base expirante, tâches planifiées interrompues par la veille) |
| Neon (gratuit) | 1 Go de stockage par projet, 100 heures de calcul par projet, mise en veille après 5 min non désactivable, restauration à un instant donné limitée à 6 h [^neon] | Base possible pour une démonstration ; la mise en veille ajoute de la latence ; insuffisant comme seule sauvegarde |
| Koyeb (gratuit) | Une instance de 512 Mo, mise en veille après 1 h d'inactivité, carte bancaire exigée [^etat] | 512 Mo est juste pour une JVM Spring Boot ; veille incompatible avec les tâches planifiées |
| Fly.io | Plus d'offre gratuite pour les nouveaux clients (essai limité) [^etat] | — |
| Railway | Crédit d'essai puis 1 $ de crédit mensuel [^etat] | Insuffisant pour un service permanent |
| Oracle Cloud « Always Free » | Machines virtuelles Arm gratuites (2 OCPU et 12 Go au total), carte et téléphone exigés, instances inactives susceptibles d'être récupérées [^etat] | **Seule option gratuite qui permet de faire tourner la pile Docker Compose complète en permanence**, avec disque persistant ; administration du système à la charge de l'équipe |

[^render]: Render, « Platforms with a real free tier for developers in 2026 », 23 avril 2026, <https://render.com/articles/platforms-with-a-real-free-tier-for-developers-in-2026>.
[^neon]: Neon, page de tarifs, consultée le 19 octobre 2026, <https://neon.com/pricing>.
[^etat]: SnapDeploy, « State of free hosting », relevé daté des 26 au 28 septembre 2026 (source tierce), <https://snapdeploy.dev/state-of-free-hosting>.

**Recommandation.**

- *Démonstration* : une machine virtuelle (Oracle Always Free ou un petit VPS payant de 2 Go de mémoire) avec
  `docker compose -f docker-compose.yml -f docker-compose.demo.yml up -d` derrière Caddy pour le HTTPS.
  Variante sans administration système : Render pour le serveur et le frontend + Neon pour la base, en acceptant la
  veille et en recréant la démonstration au besoin.
- *Exploitation réelle* : un VPS payant (2 vCPU, 4 Go) ou un hébergement géré, PostgreSQL avec sauvegardes
  automatiques **et** les sauvegardes de `scripts/backup.sh` copiées hors site, supervision de
  `/actuator/health/readiness`. La migration d'un hébergeur à l'autre se fait par sauvegarde/restauration.

## 4. Installation sur un serveur avec Docker

```bash
# 1. Prérequis : Docker Engine et le plugin Compose ; un nom de domaine pointant vers le serveur.
git clone <depot> /opt/coophabitat && cd /opt/coophabitat
cp .env.example .env && chmod 600 .env
# 2. Renseigner .env : COOPHABITAT_DB_PASSWORD, COOPHABITAT_JWT_SECRET (openssl rand -base64 48),
#    COOPHABITAT_PUBLIC_URL=https://coop.exemple.sn, COOPHABITAT_BOOTSTRAP_ADMIN_EMAIL, éventuellement SMTP et Wave.
docker compose up -d --build --wait
docker compose ps                                   # db, backend et web « healthy »
docker compose logs backend | grep "lien d'activation"   # sans SMTP : lien unique de l'administrateur initial
```

**HTTPS** avec Caddy (certificat Let's Encrypt automatique), installé sur l'hôte :

```
coop.exemple.sn {
    reverse_proxy 127.0.0.1:8090
}
```

Le conteneur `web` transmet `X-Forwarded-For` et `X-Forwarded-Proto` au serveur
(`COOPHABITAT_FORWARD_HEADERS=native`), ce qui donne la vraie adresse IP pour la limitation de débit et le journal.

**Vérifié dans l'environnement de développement** (Docker 29.8) : construction des deux images, démarrage de la
pile, en-têtes de sécurité, Swagger et `/actuator` non publiés, 14 parcours Playwright réussis contre la pile,
refus de démarrage en production avec simulateur, données de démonstration, cookie non sécurisé ou secret court,
démarrage propre en production avec invitation de l'administrateur initial. Non vérifié : un déploiement chez un
hébergeur réel avec un vrai certificat.

## 5. Sauvegardes

```bash
scripts/backup.sh /var/backups/coophabitat
# avec chiffrement : BACKUP_GPG_RECIPIENT=tresorerie@coop.exemple.sn scripts/backup.sh /var/backups/coophabitat
```

Chaque sauvegarde contient `base.dump` (`pg_dump` au format personnalisé), `documents.tar.gz`, un `MANIFESTE.txt`
(date, version de migration) et `SHA256SUMS`. Fichiers créés en `600`. Exemple de planification quotidienne
(crontab de l'utilisateur qui pilote Docker) :

```
30 2 * * * cd /opt/coophabitat && scripts/backup.sh /var/backups/coophabitat >> /var/log/coophabitat-backup.log 2>&1
45 2 * * * find /var/backups/coophabitat -maxdepth 1 -mtime +30 -exec rm -rf {} +
```

**Copier les sauvegardes hors du serveur** (autre machine, stockage objet) : une sauvegarde sur le même disque ne
protège pas d'une perte du serveur.

## 6. Restauration

```bash
scripts/restore.sh /var/backups/coophabitat/coophabitat-20261019T020000Z --confirmer coophabitat
```

Le script vérifie les empreintes, **exige le nom exact de la base** en confirmation, arrête l'application,
recrée la base, restaure les données et les documents, puis redémarre. Il remplace intégralement les données
actuelles. Après restauration, vérifier l'intégrité du journal d'audit (Bureau → Journal d'audit → Vérifier).

**Procédure testée** le 19 octobre 2026 sur la pile Docker de démonstration : sauvegarde, modifications
(nouvelles connexions), restauration, retour exact aux comptes de la sauvegarde (371 événements d'audit,
68 transactions), chaîne d'audit valide, application de nouveau disponible. Une confirmation incorrecte est
refusée. Refaire cet essai périodiquement sur une machine distincte.

## 7. Mises à jour

```bash
scripts/backup.sh /var/backups/coophabitat          # toujours avant une mise à jour
git pull && docker compose up -d --build --wait     # les migrations Flyway s'appliquent au démarrage
```

Une migration ne modifie jamais une migration déjà appliquée. En cas d'échec de migration, le serveur ne démarre
pas et la base reste dans son état précédent (transactions PostgreSQL) ; restaurer la sauvegarde si nécessaire.

## 8. Supervision minimale

- `GET /actuator/health/readiness` (dans le conteneur ou derrière le reverse proxy si on l'expose volontairement).
- Journal du serveur : `docker compose logs -f backend` ; les avertissements « SIMULATION » ou « Wave a répondu »
  méritent attention.
- Dans l'application : Bureau → Notifications (échecs d'envoi), Paiements (intentions en vérification),
  Journal d'audit (vérification de la chaîne).
