# Architecture de CoopHabitat

Ce document décrit l'organisation technique et contient les diagrammes UML demandés : cas d'utilisation,
architecture générale, classes métier, séquences de paiement et d'attribution, modèle de données et déploiement.
Les diagrammes sont écrits en [Mermaid](https://mermaid.js.org/), rendus directement par GitHub et GitLab.

## 1. Choix d'ensemble

| Couche | Technologie | Raisons |
|---|---|---|
| Interface | Angular 22 (composants autonomes, signaux, sans zone.js), SCSS sans bibliothèque d'interface | Application unique pour ordinateur et mobile ; aucun secret côté navigateur |
| API | Spring Boot 4.1, Java 21, Spring Security 7, Hibernate 7, Jackson 3 | Écosystème mature, transactions déclaratives, validation, OpenAPI |
| Données | PostgreSQL 16, Flyway | Transactions ACID, contraintes et triggers pour les invariants financiers |
| Documents | PDF générés par OpenPDF, Excel par Apache POI, stockage sur disque avec empreinte SHA-256 | Reçus, PV et rapports produits à partir des données persistées |
| Déploiement | Docker Compose : `db`, `backend`, `web` (nginx) | Séparation nette des trois composants, reproductible |

Le serveur est un **monolithe modulaire** : un paquet Java par domaine, chacun avec contrôleur, DTO, service,
dépôts et entités. Les intégrations externes (fournisseurs de paiement, courriel, SMS) sont isolées derrière des
interfaces (`PaymentProvider`, `ChannelSender`).

**Montants.** Tous les montants sont en francs CFA (XOF) sans décimale : `NUMERIC(15,0)` en base, `BigDecimal`
en Java, nombre entier dans l'API. Aucun nombre flottant. Les arrondis de pénalités suivent un mode déclaré dans
le plan (`HALF_UP` par défaut).

**Dates.** Instants stockés en UTC ; dates métier (échéances, date de référence) en `LocalDate` ; tâches
planifiées et calendrier dans le fuseau `Africa/Dakar`.

## 2. Cas d'utilisation

```mermaid
flowchart LR
    V([Visiteur])
    M([Membre])
    T([Trésorier])
    P([Président])
    S([Secrétaire])
    A([Auditeur])
    AD([Administrateur technique])
    W([Wave])

    subgraph Public
      UC1[Consulter la présentation et les projets]
      UC2[Vérifier un reçu]
    end
    subgraph Espace membre
      UC3[Consulter échéances, paiements et reçus]
      UC4[Payer une échéance]
      UC5[Candidater, consulter et recalculer un tirage]
      UC6[Contester un résultat]
      UC7[Consulter assemblées, documents, transparence]
      UC8[Demande relative à ses données]
    end
    subgraph Bureau
      UC9[Gérer les membres et leurs statuts]
      UC10[Gérer plans, échéances, pénalités]
      UC11[Saisir un encaissement manuel]
      UC12[Valider un encaissement saisi par un autre]
      UC13[Dépenses, budget, banque, rapports]
      UC14[Préparer une procédure d'attribution]
      UC15[Valider la liste, exécuter le tirage, valider le PV]
      UC16[Assemblées et documents]
      UC17[Consulter et vérifier le journal d'audit]
    end
    UC18[Gérer comptes, rôles, paramètres]
    UC19[Notifier un paiement signé]

    V --> UC1 & UC2
    M --> UC3 & UC4 & UC5 & UC6 & UC7 & UC8
    T --> UC10 & UC11 & UC13
    P --> UC12 & UC13 & UC15 & UC17
    S --> UC9 & UC14 & UC15 & UC16
    A --> UC17 & UC15
    AD --> UC18
    W --> UC19
```

## 3. Architecture générale

```mermaid
flowchart TB
    subgraph Navigateur
      SPA[Application Angular<br/>jeton d'accès en mémoire seulement]
      SW[Service worker<br/>coquille + pages publiques]
      LS[(Copie hors ligne datée<br/>sur demande du membre)]
    end
    subgraph Serveur
      NG[nginx<br/>fichiers statiques, CSP, relais /api]
      subgraph Spring Boot
        SEC[Sécurité<br/>JWT, cookie de renouvellement, permissions, limitation de débit]
        API[Contrôleurs REST /api/v1 + DTO + validation]
        SVC[Services métier<br/>membres, cotisations, paiements, comptabilité, attribution, AG]
        PRV[Adaptateurs<br/>Wave, SendFlash indisponible, simulateur]
        NOTIF[File de notifications<br/>application, courriel]
        JOBS[Tâches planifiées<br/>échéances, pénalités, rappels J-3, réconciliation]
        AUD[Journal d'audit chaîné]
        PDF[PDF / Excel]
      end
      DB[(PostgreSQL 16<br/>contraintes + triggers d'immuabilité)]
      FS[(Documents<br/>empreinte SHA-256)]
    end
    WAVE[API Wave Checkout]
    SMTP[Serveur SMTP]

    SPA -->|HTTPS| NG --> SEC --> API --> SVC
    SPA -.-> SW
    SPA -.-> LS
    SVC --> PRV -->|HTTPS| WAVE
    WAVE -->|webhook signé| NG
    SVC --> NOTIF --> SMTP
    SVC --> AUD
    SVC --> PDF --> FS
    SVC --> DB
    AUD --> DB
    JOBS --> SVC
```

**Frontend.** `core/` (client API, authentification, intercepteur, gardes, erreurs, libellés, copie hors ligne),
`ui/` (composants et pipes partagés), `layout/` (coquilles publique et connectée, bandeaux), `features/`
(un dossier par espace : `public`, `auth`, `account`, `member`, `bureau`, `bureau/finance`). Les types de l'API
(`core/api-types.ts`) sont générés depuis le contrat OpenAPI, ce qui garde frontend et serveur cohérents.
Toutes les routes sont chargées à la demande ; les écrans du bureau sont protégés par des gardes de permission,
mais **c'est le serveur qui décide** : chaque route REST porte son contrôle d'autorisation.

## 4. Classes métier principales

```mermaid
classDiagram
    direction LR
    class Member {
      memberNumber
      status : EN_ATTENTE|ACTIF|SUSPENDU|RADIE
      memberType
      membershipDate
    }
    class UserAccount { email; roles; memberId; failedAttempts; lockedUntil }
    class ContributionPlan { amount; periodicity; dueDay; graceDays; penaltyMethod; penaltyCap; status }
    class MemberObligation { kind : COTISATION|PENALITE; periodLabel; dueDate; amountDue; amountPaid; status }
    class PaymentIntent { reference; amount; provider; status; idempotencyKey; simulated }
    class PaymentTransaction { amount; providerTransactionId; source; status; simulated }
    class PaymentAllocation { amount }
    class Receipt { number; verificationCode; documentId }
    class AccountingEntry { reference; entryDate; reversalOf }
    class AccountingLine { accountCode; debit; credit }
    class Expense { amount; status; createdBy; approvedBy; paidBy }
    class PropertyLot { reference; type; area; status }
    class AllocationCampaign { reference; method; status; seedCommitment; participantsDigest; lotsDigest }
    class AllocationCandidate { eligibilityStatus; eligibilityReason; snapshot }
    class AllocationResult { drawRank; status : PROVISOIRE|DEFINITIF|LISTE_ATTENTE|ANNULE }
    class AllocationApproval { step; decision; approverId }
    class GeneralAssembly { type; scheduledAt; status }
    class AuditEvent { action; actor; previousHash; hash }

    UserAccount "0..1" --> "0..1" Member
    Member "*" --> "1" ContributionPlan
    Member "1" --> "*" MemberObligation
    Member "1" --> "*" PaymentIntent
    PaymentIntent "1" --> "0..1" PaymentTransaction
    PaymentTransaction "1" --> "*" PaymentAllocation
    PaymentAllocation "*" --> "1" MemberObligation
    PaymentTransaction "1" --> "1" Receipt
    PaymentTransaction "1" --> "1" AccountingEntry
    Expense "1" --> "0..1" AccountingEntry
    AccountingEntry "1" --> "2..*" AccountingLine
    AllocationCampaign "*" --> "*" PropertyLot
    AllocationCampaign "1" --> "*" AllocationCandidate
    AllocationCandidate "1" --> "0..1" AllocationResult
    AllocationResult "*" --> "0..1" PropertyLot
    AllocationCampaign "1" --> "*" AllocationApproval
```

## 5. Séquence de paiement (Wave)

```mermaid
sequenceDiagram
    autonumber
    actor M as Membre
    participant UI as Angular
    participant API as Serveur CoopHabitat
    participant DB as PostgreSQL
    participant W as Wave
    M->>UI: Choisit une échéance et « Payer avec Wave »
    UI->>API: POST /payments/intents (Idempotency-Key)
    API->>API: Vérifie identité, droits, échéance du membre, montant ≤ dû + avance autorisée
    API->>DB: Intention INITIE (clé d'idempotence unique par membre)
    API->>W: POST /v1/checkout/sessions (montant, XOF, client_reference)
    alt Wave indisponible
      W-->>API: 5xx / délai dépassé
      API-->>UI: 502 « demande conservée, réessayez » (même clé = même intention)
    else Session créée
      W-->>API: id, wave_launch_url
      API->>DB: Intention EN_ATTENTE
      API-->>UI: launchUrl
      UI->>W: Redirection vers la page Wave officielle
      M->>W: Paie depuis son application Wave
      W->>API: Webhook checkout.session.completed (Wave-Signature)
      API->>API: Vérifie HMAC-SHA256 et horodatage (5 min), rejette les doublons d'événement
      API->>W: GET /v1/checkout/sessions/{id} (double contrôle)
      alt Wave confirme « succeeded »
        API->>DB: Verrou de l'intention, transaction CONFIRME (une seule), imputation sur les échéances ,<br/>écriture débit 552100, crédit 467100 (et 758100 pour les pénalités), reçu numéroté, audit
        API-->>M: Notification « paiement confirmé » + reçu PDF
      else Wave ne confirme pas
        API->>DB: Intention EN_VERIFICATION, rien n'est compté
      end
    end
    Note over API,W: Webhook perdu : la réconciliation planifiée interroge Wave et confirme une seule fois.
    UI->>API: GET /payments/intents/by-reference (toutes les 5 s)
    Note over UI: « Paiement confirmé » n'est affiché qu'à l'état CONFIRME.
```

États d'une intention de paiement :

```mermaid
stateDiagram-v2
    [*] --> INITIE
    INITIE --> EN_ATTENTE: session ouverte chez le fournisseur
    INITIE --> ECHOUE: refus définitif / jamais ouverte (1 h)
    EN_ATTENTE --> CONFIRME: webhook signé + vérification, ou réconciliation
    EN_ATTENTE --> EN_VERIFICATION: succès annoncé mais non confirmé
    EN_VERIFICATION --> CONFIRME: vérification ultérieure
    EN_ATTENTE --> ECHOUE
    EN_ATTENTE --> EXPIRE
    CONFIRME --> REMBOURSE: remboursement motivé (contre-écriture)
    note right of EN_VERIFICATION
        Encaissement manuel saisi par un responsable,
        confirmé par un second.
    end note
```

## 6. Séquence d'attribution par tirage au sort

```mermaid
sequenceDiagram
    autonumber
    actor S as Secrétariat
    actor M as Membres
    actor V as Deux validateurs distincts
    participant API as Serveur
    participant DB as PostgreSQL
    S->>API: Créer la procédure, choisir les lots (lots DISPONIBLE → RESERVE)
    S->>API: Publier les critères (figés ensuite)
    S->>API: Ouvrir les candidatures
    M->>API: Candidater / se retirer
    S->>API: Clore, vérifier l'éligibilité à la date de référence
    API->>DB: Motif et instantané d'éligibilité par candidat
    S->>API: Publier la liste
    API->>API: Empreintes SHA-256 des participants et des lots ,<br/>graine secrète de 256 bits, publication de son engagement SHA-256
    V->>API: Valider la liste (2 approbations, aucun candidat ni doublon)
    V->>API: Exécuter le tirage avec l'aléa public saisi en séance
    API->>API: graine finale = SHA-256(version, graine, empreintes, aléa), Fisher-Yates HMAC-SHA256
    API->>DB: Résultats (rangs, lots, liste d'attente), exécution unique, PV PDF versionné
    V->>API: Valider le PV (2 approbations) → résultats DEFINITIF, lots ATTRIBUE
    S->>API: Publier les résultats (graine révélée)
    API-->>M: Notification aux bénéficiaires
    M->>M: Recalcul indépendant (tools/verifier_tirage.py) ou dans l'application
    M->>API: Contestation motivée éventuelle → décision tracée
```

États d'une procédure : `BROUILLON → CRITERES_PUBLIES → CANDIDATURES_OUVERTES → CANDIDATURES_CLOSES →
LISTE_PUBLIEE → VALIDEE → EXECUTEE → PV_VALIDE → RESULTATS_PUBLIES → ARCHIVEE`, avec `ANNULEE` possible avant
l'exécution. Le détail est dans [attribution-des-lots.md](attribution-des-lots.md).

## 7. Modèle de données

52 tables (migration `V1`). Vue des entités principales et de leurs relations :

```mermaid
erDiagram
    user_account }o--o| member : "compte de"
    user_account ||--o{ refresh_token : ""
    member }o--|| contribution_plan : "suit"
    member ||--o{ membership_status_history : "historique"
    member ||--o{ member_obligation : "doit"
    member_obligation ||--o{ obligation_adjustment : "dérogations motivées"
    member ||--o{ payment_intent : "initie"
    payment_intent ||--o| payment_transaction : "confirmée en"
    payment_transaction ||--o{ payment_allocation : "imputée"
    payment_allocation }o--|| member_obligation : "sur"
    payment_transaction ||--|| receipt : "reçu"
    payment_provider_event }o--o| payment_intent : "webhooks"
    accounting_entry ||--|{ accounting_line : "lignes équilibrées"
    accounting_line }o--|| financial_account : "compte"
    accounting_entry |o--o| accounting_entry : "contre-écriture de"
    expense ||--o{ expense_approval : ""
    budget ||--|{ budget_line : ""
    bank_statement_line }o--o| accounting_entry : "rapprochée"
    property_project ||--o{ property_lot : ""
    property_lot ||--o{ lot_status_history : ""
    allocation_campaign ||--|{ allocation_campaign_lot : ""
    allocation_campaign_lot }o--|| property_lot : ""
    allocation_campaign ||--o{ allocation_candidate : ""
    allocation_candidate }o--|| member : ""
    allocation_candidate ||--o| allocation_result : ""
    allocation_result }o--o| property_lot : ""
    allocation_campaign ||--o{ allocation_approval : ""
    allocation_campaign ||--o{ allocation_minutes : ""
    allocation_campaign ||--o{ allocation_contest : ""
    general_assembly ||--o{ agenda_item : ""
    agenda_item ||--o{ resolution : ""
    general_assembly ||--o{ attendance : ""
    notification ||--o{ notification_attempt : ""
    document ||--o{ receipt : "PDF archivé"
```

Règles d'intégrité portées par la base (et pas seulement par le code) :

| Règle | Mécanisme |
|---|---|
| Journaux en ajout seul (`audit_event`, `accounting_entry`, `accounting_line`, `payment_allocation`, historiques de statut, approbations, tentatives de notification) | trigger refusant `UPDATE`, `DELETE`, `TRUNCATE` |
| Pas de suppression physique des membres, paiements, reçus, échéances, dépenses, rapports, lots, procédures, résultats, documents, assemblées, résolutions | trigger refusant `DELETE`/`TRUNCATE` (archivage par statut) |
| Écriture comptable équilibrée, au moins deux lignes | contrainte différée vérifiée en fin de transaction |
| Transaction confirmée : montant, membre, fournisseur, caractère simulé figés ; seul passage autorisé vers `REMBOURSE` | trigger |
| Reçu et contenu d'un document archivé figés | trigger |
| Une seule transaction par intention, un seul événement par identifiant fournisseur, une clé d'idempotence par membre | contraintes d'unicité |
| Un lot ne peut avoir qu'un résultat actif et n'être engagé que dans une procédure | index uniques partiels + verrous `SELECT … FOR UPDATE` |
| Concurrence sur les paiements et l'imputation | verrou pessimiste sur l'intention et sur le dossier du membre ; versions optimistes sur les entités modifiables |

Journal d'audit : chaque événement contient l'empreinte SHA-256 du précédent ; `GET /api/v1/audit/verify`
recalcule la chaîne et signale le premier maillon altéré.

## 8. Déploiement

```mermaid
flowchart LR
    U[Navigateurs<br/>ordinateur et mobile] -->|HTTPS 443| RP[Reverse proxy TLS<br/>Caddy / Traefik / nginx]
    WV[Wave] -->|webhook HTTPS| RP
    subgraph Hôte Docker
      RP -->|127.0.0.1:8090| WEB[web<br/>nginx-unprivileged 1.28<br/>Angular + CSP]
      WEB -->|réseau interne| BE[backend<br/>JRE 21, utilisateur non root<br/>sonde readiness]
      BE --> DB[(db<br/>postgres:16-alpine<br/>volume db-data)]
      BE --> VOL[(volume documents)]
      CRON[cron : scripts/backup.sh] -.-> DB
      CRON -.-> VOL
    end
    BE -->|HTTPS sortant| WAPI[API Wave]
    BE -->|SMTP| MAIL[Serveur de courriel]
    CRON -.->|copie chiffrée hors site| OFF[(Stockage externe)]
```

La base et le serveur ne sont jamais exposés sur l'hôte ; seul le conteneur `web` l'est, sur `127.0.0.1` par
défaut. Détails : [deploiement.md](deploiement.md).

## 9. Mode hors ligne partiel

- Le service worker met en cache la coquille de l'application, les fichiers statiques et trois pages publiques
  (présentation, projets, documents publics), en « réseau d'abord ». Il **ne met jamais en cache l'API privée**.
- Un membre peut, s'il le demande, enregistrer une copie de sa situation sur son appareil. Elle est liée à son
  compte, datée, affichée avec la mention qu'elle n'est pas à jour, et effacée à la déconnexion (le service
  worker vide aussi ses caches).
- Aucune opération financière n'est possible hors ligne ; un bandeau signale la perte de connexion.
