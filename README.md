# CoopHabitat

Application web de gestion d'une coopérative d'habitat au Sénégal : adhérents, cotisations et pénalités,
paiements (Wave, encaissements manuels, simulateur signalé), reçus PDF vérifiables, comptabilité en partie double
et transparence financière, programmes immobiliers et lots, attribution des lots par une procédure traçable
(tirage au sort vérifiable ou décision de commission), assemblées générales, documents, notifications,
tableaux de bord par rôle et consultation hors ligne partielle.

> **État du projet.** Logiciel fonctionnel et testé, prêt pour une **démonstration**. Il n'a fait l'objet d'aucun
> audit de sécurité, juridique ou comptable indépendant : il n'est ni certifié ni réputé conforme. Avant toute
> gestion de fonds réels, lire la section [Limites connues](#13-limites-connues) et
> [le rapport final](docs/rapport-final.md).

| Ce qui est réel | Ce qui est simulé ou en attente |
|---|---|
| Calcul des échéances et pénalités, imputation des paiements, reçus PDF, comptabilité, rapports PDF/Excel, attribution, audit chaîné, rappels J-3 (dans l'application) | **Simulateur de paiement** : toujours marqué « SIMULATION — AUCUN ARGENT RÉEL TRANSFÉRÉ », interdit en production |
| Adaptateur **Wave Checkout** complet (création de session, webhook signé, vérification d'état, remboursement), testé contre un faux serveur Wave | Wave réel : **en attente d'un contrat marchand et de clés** (non testé contre l'API de production) |
| Courriel par SMTP, si un serveur est fourni | **SendFlash** : adaptateur volontairement **indisponible** (aucune documentation officielle confirmée) ; **SMS** : aucun fournisseur intégré |
| Garde des fonds suivie comptablement | Aucun séquestre ni cantonnement : l'application affiche « Fonds suivis comptablement — séquestre externe non vérifié ou non intégré » |

---

## Sommaire

1. [Prérequis](#1-prérequis)
2. [Installation](#2-installation)
3. [Variables d'environnement](#3-variables-denvironnement)
4. [Démarrage de PostgreSQL](#4-démarrage-de-postgresql)
5. [Migrations](#5-migrations)
6. [Démarrage du serveur](#6-démarrage-du-serveur)
7. [Démarrage du frontend](#7-démarrage-du-frontend)
8. [Lancement des tests](#8-lancement-des-tests)
9. [Accès à Swagger](#9-accès-à-swagger)
10. [Données de démonstration](#10-données-de-démonstration)
11. [Modes simulation et production](#11-modes-simulation-et-production)
12. [Déploiement](#12-déploiement)
13. [Limites connues](#13-limites-connues)
14. [Organisation du dépôt](#14-organisation-du-dépôt)
15. [Documentation détaillée](#15-documentation-détaillée)

**Démarrage le plus court (démonstration complète, Docker seul requis) :**

```bash
COOPHABITAT_DEMO_PASSWORD='Demo-CoopHabitat-2026' scripts/demarrer-demo.sh
# puis ouvrir http://localhost:8090 et se connecter avec presidence@demo.coophabitat.invalid
```

Le site est publié sur le port **8090** par défaut. S'il est occupé, le script prend le premier port libre
suivant et affiche l'adresse à ouvrir ; pour imposer un port : `COOPHABITAT_HTTP_PORT=9000 scripts/demarrer-demo.sh`.

**Version officielle locale (vrais comptes, sans données fictives ni simulateur) :**

```bash
scripts/demarrer-officiel.sh vous@exemple.sn          # Linux / macOS
powershell -ExecutionPolicy Bypass -File scripts\demarrer-officiel.ps1 -Admin vous@exemple.sn   # Windows
```

Elle tourne sur le port **8091**, à côté de la démonstration, avec ses propres données. Au premier lancement, le
script crée `.env.officiel` (secrets aléatoires, à ne jamais partager) et affiche le lien d'activation à usage unique
du premier administrateur. Voir [Demandes d'adhésion en ligne](#demandes-dadhésion-en-ligne).

---

## 1. Prérequis

| Outil | Version utilisée et vérifiée | Usage |
|---|---|---|
| Docker + Docker Compose v2 | Docker 29.8 | Démonstration, déploiement, tests d'intégration (Testcontainers) |
| JDK | 21 (Temurin/OpenJDK) | Serveur Spring Boot 4.1 |
| Maven | 3.9 | Construction et tests du serveur |
| Node.js + npm | Node 24 | Frontend Angular 22 |
| Python | 3.10 ou plus | Vérificateur indépendant du tirage (`tools/`), facultatif |

Pour la seule démonstration, Docker suffit : la construction des images télécharge Maven et Node.

## 2. Installation

```bash
git clone <adresse-du-depot> coophabitat     # ou décompresser l'archive fournie
cd coophabitat
cp .env.example .env                         # puis compléter .env (voir section 3)
(cd frontend && npm ci)                      # uniquement pour le développement du frontend
```

Le dépôt ne contient aucun secret. `.env`, les sauvegardes (`backups/`) et les données locales (`data/`) sont
ignorés par Git.

## 3. Variables d'environnement

Le modèle complet et commenté est [`.env.example`](.env.example). Les variables indispensables :

| Variable | Rôle | Obligatoire |
|---|---|---|
| `COOPHABITAT_ENVIRONMENT` | `DEVELOPPEMENT`, `TEST`, `DEMONSTRATION` ou `PRODUCTION` | oui |
| `COOPHABITAT_DB_PASSWORD` | Mot de passe PostgreSQL | oui |
| `COOPHABITAT_JWT_SECRET` | Secret de signature des jetons, **32 caractères minimum**, aléatoire (`openssl rand -base64 48`) | oui |
| `COOPHABITAT_PUBLIC_URL` | Adresse HTTPS publique (liens des courriels, CORS) | oui en production |
| `COOPHABITAT_BOOTSTRAP_ADMIN_EMAIL` | Adresse du premier administrateur technique (invitation à usage unique) | au premier démarrage |
| `COOPHABITAT_COOKIE_SECURE` | Cookie de session `Secure` ; `true` exigé en production | — |
| `COOPHABITAT_WAVE_*` | Clé API marchande, secret de webhook, URL de retour | pour activer Wave |
| `COOPHABITAT_SMTP_*`, `COOPHABITAT_MAIL_*` | Serveur de courriel | facultatif |
| `COOPHABITAT_PAYMENT_MOCK_ENABLED` | Simulateur de paiement ; **refusé en production** | — |
| `COOPHABITAT_DEMO_ENABLED`, `COOPHABITAT_DEMO_PASSWORD` | Jeu de démonstration ; **refusé en production** | — |

Le serveur **refuse de démarrer** si la configuration est dangereuse : secret JWT trop court, ou, en
production, simulateur actif, données de démonstration actives, cookie non sécurisé, secret ressemblant à un
secret de développement (`ProductionSafetyCheck`, vérifié par des tests et sur l'image Docker réelle).

## 4. Démarrage de PostgreSQL

PostgreSQL 16 est requis. Pour le développement, une base en conteneur exposée sur `127.0.0.1:5432` :

```bash
docker compose -f docker-compose.dev.yml up -d        # base « coophabitat », utilisateur « coophabitat », mot de passe devpass
```

Une installation PostgreSQL locale convient aussi :
`CREATE DATABASE coophabitat; CREATE USER coophabitat WITH PASSWORD '...'; ALTER DATABASE coophabitat OWNER TO coophabitat;`

## 5. Migrations

Le schéma est géré par **Flyway** ; les migrations s'exécutent automatiquement au démarrage du serveur
(`backend/src/main/resources/db/migration`) :

| Version | Contenu |
|---|---|
| `V1__schema_initial.sql` | 52 tables, contraintes de clés étrangères, d'unicité et de statut, index |
| `V2__integrite_et_referentiels.sql` | Triggers d'immuabilité (journaux en ajout seul, écritures non modifiables, transactions confirmées figées, reçus et documents figés), contrôle d'équilibre des écritures, rôles, permissions, plan de comptes, durées de conservation |
| `V3__sequences.sql` | Séquences de numérotation (reçus, références) |
| `V4__validation_adhesions.sql` | Statut `REFUSE` des demandes d'adhésion refusées |
| `V5__declarations_de_paiement.sql` | Consignes de paiement de la coopérative, compte de trésorerie Orange Money |

Hibernate est en mode `validate` : il ne modifie jamais le schéma. Une migration déjà appliquée ne doit jamais
être modifiée ; toute évolution passe par un nouveau fichier `V4__…`.

## 6. Démarrage du serveur

```bash
cd backend
export COOPHABITAT_ENVIRONMENT=DEVELOPPEMENT COOPHABITAT_DB_PASSWORD=devpass \
       COOPHABITAT_JWT_SECRET="$(openssl rand -base64 48)" COOPHABITAT_COOKIE_SECURE=false
# facultatif, pour une démonstration locale complète :
export COOPHABITAT_PAYMENT_MOCK_ENABLED=true COOPHABITAT_PAYMENT_MOCK_SECRET=simulation-locale \
       COOPHABITAT_DEMO_ENABLED=true COOPHABITAT_DEMO_PASSWORD='Demo-CoopHabitat-2026'
mvn spring-boot:run
```

Le serveur écoute sur <http://localhost:8080>. Sonde de disponibilité : `/actuator/health/readiness`
(elle ne passe à `UP` qu'après les migrations et le chargement éventuel des données de démonstration).
En développement, les liens d'activation et de réinitialisation sont écrits dans le journal du serveur
(aucun SMTP requis) ; jamais dans les autres environnements.

## 7. Démarrage du frontend

```bash
cd frontend
npm ci
npm start            # http://localhost:4200, /api relayé vers http://localhost:8080 (proxy.conf.json)
```

Après une modification de l'API, `npm run api-types` régénère les types TypeScript depuis `docs/openapi.json`.
Le frontend ne contient aucun secret : il ne connaît que l'API de son propre domaine.

## 8. Lancement des tests

| Commande | Contenu | Résultat de la dernière exécution |
|---|---|---|
| `cd backend && mvn -B verify` | 77 tests JUnit (unitaires + intégration sur PostgreSQL 16 réel via Testcontainers) et contrôle de couverture JaCoCo (échec si < 60 % des lignes) | **77/77 réussis, couverture 79,7 % des lignes, 58,3 % des branches** |
| `python3 -m unittest discover tools` | Vecteur de référence du tirage, identique à celui du test Java | réussi |
| `cd frontend && npm run lint` | ESLint + règles d'accessibilité des gabarits Angular | aucune erreur |
| `cd frontend && npm test` | 13 tests Vitest (cœur, pipes, composants) | 13/13 réussis |
| `cd frontend && npx playwright test` | 7 parcours × 2 appareils (ordinateur, Pixel 7) sur données de démonstration fraîches | **14/14 réussis** (serveur de dev et pile Docker) |
| `scripts/verifier-tout.sh` | Tout ce qui précède sauf Playwright | — |

Les tests d'intégration exigent Docker. Les parcours Playwright exigent une pile démarrée avec des données de
démonstration **fraîches** (un parcours paie une échéance) ; détails, correspondance avec les scénarios
obligatoires et résultats : [docs/tests.md](docs/tests.md). Rapport de couverture HTML :
`backend/target/site/jacoco/index.html`.

## 9. Accès à Swagger

- Interface : <http://localhost:8080/swagger-ui.html> (serveur lancé directement, section 6).
- Contrat OpenAPI 3 exporté : [`docs/openapi.json`](docs/openapi.json) (154 chemins) ; en direct : `/v3/api-docs`.
- Dans la pile Docker, nginx **ne publie pas** Swagger ni `/actuator` ; en production `COOPHABITAT_SWAGGER_ENABLED=false`
  par défaut.
- Pour appeler une route protégée : `POST /api/v1/auth/login` avec l'en-tête `X-Requested-With: CoopHabitat`,
  puis bouton *Authorize* avec le `accessToken` reçu.

## 10. Données de démonstration

Activées **uniquement** par `COOPHABITAT_DEMO_ENABLED=true` avec un mot de passe commun choisi par la personne
qui lance la démonstration (`COOPHABITAT_DEMO_PASSWORD`, 12 caractères minimum ; aucun mot de passe n'est figé
dans le code). Le chargement est refusé en production, se fait en une seule transaction, passe par les services
métier réels (mêmes règles, mêmes écritures comptables, même audit) et n'a lieu qu'une fois.

Toutes les identités sont fictives, marquées « (fictif) », sur le domaine réservé `demo.coophabitat.invalid` ;
chaque enregistrement porte `is_demo = true` et l'interface affiche un bandeau « Démonstration ».

| Compte (`…@demo.coophabitat.invalid`) | Rôle | Ce qu'on y voit |
|---|---|---|
| `presidence` | Présidente | Tableau de bord, validations (dépenses, encaissements, attributions), audit |
| `tresorerie` | Trésorier | Retards, encaissements, comptabilité, budget, banque, rapports |
| `secretariat` | Secrétaire | Membres, lots, procédures d'attribution, assemblées, documents |
| `audit` | Auditeur | Lecture seule, vérification des tirages et du journal |
| `admin` | Administrateur technique | Comptes, rôles, paramètres ; aucun droit financier |
| `membre1` … `membre8` | Membres | À jour (membre1, membre5), en retard (membre2, 3, 4, 6, 7), en attente d'activation (membre8) |

Contenu : un plan mensuel de 25 000 FCFA avec pénalité forfaitaire ; 5 mois d'échéances ; encaissements
manuels à double validation ; **un paiement simulé confirmé et un en attente** ; 6 lots fictifs dont
**2 attribués** par une procédure terminée (tirage exécuté, PV validé par deux responsables, résultats publiés,
recalculable par les membres) et 3 engagés dans une procédure ouverte aux candidatures ; une assemblée convoquée ;
une dépense en attente d'approbation avec justificatif fictif ; **un rapport financier publié**.

## 11. Modes simulation et production

| | `DEVELOPPEMENT` / `TEST` / `DEMONSTRATION` | `PRODUCTION` |
|---|---|---|
| Simulateur de paiement | autorisé si activé, bandeau et badge « SIMULATION — AUCUN ARGENT RÉEL TRANSFÉRÉ » partout, compte comptable séparé 559900 exclu de la trésorerie | **démarrage refusé** |
| Données de démonstration | autorisées si activées | **démarrage refusé** |
| Cookie de session `Secure` | facultatif (HTTP local) | **obligatoire** |
| Liens d'activation dans le journal | en développement seulement | non (sauf l'invitation unique du premier administrateur sans SMTP) |
| Wave | actif seulement si clé API, secret de webhook et URL de retour sont fournis | idem |

Un paiement n'est **compté** qu'à l'état `CONFIRME`, atteint uniquement par : un webhook signé (HMAC-SHA256,
tolérance de 5 minutes) **et** confirmé par une relecture de l'état chez Wave ; une vérification d'état chez Wave
lors de la réconciliation ; ou un encaissement manuel validé par une **seconde** personne. Une demande de paiement
n'est jamais une preuve de paiement. Détails : [docs/paiements.md](docs/paiements.md).

### Demandes d'adhésion en ligne

Avec `COOPHABITAT_PUBLIC_REGISTRATION=true` (désactivé par défaut), le site affiche « Adhérer » et la page
`/inscription`. La personne crée son compte, rattaché à un dossier de membre **en attente** ; elle peut se connecter
mais, comme pour tout membre en attente, les paiements et les candidatures restent fermés. Le bureau est prévenu et
traite toutes les demandes sur une seule page, **Demandes d'adhésion** (`/bureau/adhesions`) : la présidence ou le
secrétariat **approuve** en choisissant le plan de cotisation (affectation du plan et activation en une seule décision),
ou **refuse** avec un motif (statut `REFUSE`, accès en ligne fermé, réexamen possible depuis la fiche). La trésorerie peut
préparer un dossier en affectant le plan à l'avance. Pour les membres déjà inscrits, la trésorerie affecte ou
change un plan depuis la fiche du membre, ou en une fois depuis la page Membres (filtre « Sans plan de cotisation »,
cases à cocher, « Affecter ce plan ») ; personne n'affecte son propre plan. La personne est prévenue (espace membre et courriel) ; chaque
décision est historisée et journalisée. Nul ne valide sa propre adhésion, sauf à la mise en route (voir ci-dessous). Une adresse ou un téléphone déjà inscrits reçoivent la
même réponse qu'une demande acceptée, pour ne pas révéler qui est inscrit ; les demandes sont limitées à 5 par minute
et par adresse IP.

**Payer sans paiement en ligne.** Tant que Wave n'est pas branché, le membre paie lui-même (Wave ou Orange Money vers le
numéro de la coopérative, virement, chèque) en suivant les consignes que la trésorerie saisit dans **Paiements**, puis
**déclare** son paiement avec la référence reçue. La déclaration reste « en vérification » et n'est comptée qu'après
validation par un responsable habilité autre que le déclarant, qui a retrouvé l'argent sur le compte ; une référence ne
peut être déclarée qu'une fois. Les espèces restent saisies par la trésorerie puis vérifiées par une seconde personne.

**Sans serveur de courriel.** Le lien d'activation d'un compte créé par le bureau, ou d'une invitation renvoyée, s'affiche
au responsable pour qu'il le transmette lui-même ; l'administrateur peut aussi créer un lien « mot de passe perdu ». La
personne en est prévenue dans l'application et chaque lien est journalisé. Configurer `COOPHABITAT_SMTP_*` rétablit
l'envoi par courriel.

Les échéances d'un membre apparaissent dès la validation de son adhésion ou l'affectation de son plan.

Mise en route d'une installation officielle : l'administrateur initial active son compte avec le lien affiché,
renseigne le nom de la coopérative (Administration), puis attribue les rôles du bureau (Président, Trésorier,
Secrétaire…) aux comptes concernés, après que ces personnes ont demandé leur adhésion. La carte « Mise en route du
bureau » de l'onglet Comptes et rôles indique ce qui reste à faire. La trésorerie crée et active un plan de cotisation ;
tant qu'aucun autre responsable habilité n'a d'adhésion active, le premier président ou secrétaire désigné valide sa
propre adhésion (cas signalé dans le journal d'audit), puis celles des autres. L'administrateur technique ne valide
jamais lui-même d'adhésion. Sans serveur SMTP, la
réinitialisation de mot de passe par courriel n'est pas disponible : configurer `COOPHABITAT_SMTP_*` pour l'ouvrir.

## 12. Déploiement

```bash
cp .env.example .env && nano .env                # secrets, adresse publique, administrateur initial
docker compose up -d --build --wait               # db + backend + web (nginx), port 127.0.0.1:8090
docker compose logs backend | grep "lien d'activation"   # sans SMTP : lien unique du premier administrateur
```

Placer un reverse proxy HTTPS (Caddy, Traefik, nginx + Let's Encrypt) devant le port 8090. Planifier
`scripts/backup.sh` chaque jour et copier les sauvegardes hors du serveur ; restauration :
`scripts/restore.sh <dossier> --confirmer <nom_de_la_base>` (procédure vérifiée : voir docs/deploiement.md).

Le guide complet ([docs/deploiement.md](docs/deploiement.md)) compare les hébergements gratuits vérifiés en
octobre 2026, explique pourquoi une offre « qui se met en veille » ne convient pas (tâches planifiées, base
expirante) et distingue **démonstration universitaire** et **exploitation réelle de fonds collectifs**.

## 13. Limites connues

- **Aucun audit indépendant** (sécurité, conformité à la loi n° 2008-12 et aux exigences de la CDP, comptabilité
  SYSCOHADA). Le plan de comptes et les durées de conservation sont indicatifs et à valider.
- **Wave non testé en réel** : l'adaptateur suit la documentation publique et est testé contre un faux serveur ;
  un essai avec un compte marchand est indispensable. **SendFlash** non intégré. **Aucun SMS**.
- **Pas de signature électronique** des reçus et PV : un reçu est vérifiable en ligne (numéro + code), il n'est
  pas signé cryptographiquement.
- **Pas de séquestre** : les fonds ne sont que suivis comptablement ; le solde bancaire est *déclaré* ou importé
  d'un relevé, jamais lu directement auprès de la banque.
- **Vote électronique non implémenté** : les résultats des votes tenus en séance sont saisis par le secrétariat.
- **Hors ligne partiel** : seule une copie datée de la situation personnelle, explicitement demandée par le
  membre, est conservée sur l'appareil ; elle est affichée comme non à jour. Aucun paiement hors ligne.
- **Une seule instance** recommandée : les tâches planifiées sont protégées par un verrou en base, mais la
  limitation de débit est en mémoire (par instance).
- Les tests Playwright couvrent les parcours principaux, pas chaque écran ; aucun test de charge.

Liste complète et étapes avant une utilisation réelle : [docs/rapport-final.md](docs/rapport-final.md).

## 14. Organisation du dépôt

```
backend/            Spring Boot 4.1 (Java 21) — monolithe modulaire, un paquet par domaine
  src/main/java/sn/coophabitat/
    security/ config/ common/ audit/            authentification, configuration, erreurs, journal chaîné
    member/ contribution/ payment/ receipt/     adhérents, échéances et pénalités, paiements, reçus
    accounting/ finance/ reporting/             partie double, dépenses, budget, banque, rapports PDF/Excel
    property/ allocation/ assembly/ document/   lots, attribution, assemblées, documents
    notification/ jobs/ dashboard/ me/ admin/ publicsite/ demo/
  src/main/resources/db/migration/             migrations Flyway
  src/test/java/…/unit, …/it                    tests unitaires et d'intégration (Testcontainers)
frontend/           Angular 22 (standalone, signaux, zoneless)
  src/app/core/ ui/ layout/ features/{public,auth,account,member,bureau}
  e2e/                                          parcours Playwright
deploy/nginx/       configuration nginx et en-têtes de sécurité
scripts/            démarrage démo, vérifications, sauvegarde, restauration
tools/              vérificateur indépendant du tirage (Python, bibliothèque standard)
docs/               documentation, diagrammes, OpenAPI
.github/workflows/  intégration continue
```

Branches Git : `main` (stable), `develop` (intégration), `feature/*` (fonctionnalités). Aucun dépôt distant n'a
été créé.

## 15. Documentation détaillée

| Document | Contenu |
|---|---|
| [docs/architecture.md](docs/architecture.md) | Architecture, modèle de données, diagrammes UML (cas d'utilisation, classes, séquences, déploiement) |
| [docs/securite.md](docs/securite.md) | Menaces et protections (OWASP), rôles et permissions, données personnelles |
| [docs/paiements.md](docs/paiements.md) | Flux de paiement, Wave, SendFlash, simulateur, réconciliation, remboursements |
| [docs/attribution-des-lots.md](docs/attribution-des-lots.md) | Procédure d'attribution et explication du tirage pour les membres |
| [docs/comptabilite.md](docs/comptabilite.md) | Partie double, transparence, garde des fonds, rapports |
| [docs/deploiement.md](docs/deploiement.md) | Hébergement, HTTPS, sauvegardes, restauration, mises à jour |
| [docs/tests.md](docs/tests.md) | Stratégie de test, scénarios obligatoires, résultats |
| [docs/api.md](docs/api.md) | Conventions de l'API REST |
| [docs/rapport-final.md](docs/rapport-final.md) | Bilan final en 10 points |
