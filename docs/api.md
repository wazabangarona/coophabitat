# API REST : conventions

Contrat complet : [`openapi.json`](openapi.json) (OpenAPI 3, 154 chemins), interface Swagger sur
`/swagger-ui.html` lorsque `COOPHABITAT_SWAGGER_ENABLED=true`. Les types TypeScript du frontend sont générés
depuis ce fichier (`npm run api-types`).

## Organisation

Toutes les routes sont sous `/api/v1`, regroupées par domaine :

| Préfixe | Domaine | Chemins |
|---|---|---|
| `/auth` | Connexion, renouvellement, déconnexion, mot de passe, invitation | 10 |
| `/me` | Espace personnel : profil, coordonnées, situation, échéances, export, demandes sur les données | 6 |
| `/public` | Site public : présentation, projets, documents publics, vérification de reçu | 6 |
| `/members` | Membres, statuts, historique, échéances, export | 9 |
| `/contributions` | Plans de cotisation, génération, pénalités, dérogations | 8 |
| `/payments` | Intentions, simulation, encaissements manuels, remboursements, webhooks | 15 |
| `/receipts`, `/members/{id}/receipts` | Reçus PDF | 2 |
| `/finance` | Synthèse, écritures, balance, dépenses, budgets, banque, rapports, export | 27 |
| `/property` | Programmes et lots | 6 |
| `/allocations` | Procédures d'attribution, vue membre, vérification | 27 |
| `/assemblies` | Assemblées générales | 12 |
| `/documents` | Documents | 5 |
| `/notifications` | Boîte de réception, préférences, supervision de la file | 11 |
| `/dashboard`, `/audit`, `/admin` | Tableaux de bord, journal d'audit, administration | 13 |

Les entités JPA ne sont jamais exposées : chaque route reçoit et renvoie des DTO (`record` Java) dédiés.

## Authentification

1. `POST /api/v1/auth/login` `{ "email", "password" }` avec `X-Requested-With: CoopHabitat` → `{ accessToken, expiresIn, user }`
   et cookie `CH_REFRESH` (HttpOnly).
2. Chaque appel : `Authorization: Bearer <accessToken>` (validité 15 minutes).
3. `POST /api/v1/auth/refresh` (cookie + `X-Requested-With`) → nouveau jeton ; le cookie est renouvelé à chaque fois.

## Formats

- JSON en UTF-8. Montants : **entiers en francs CFA** (`25000`), jamais de décimales ni de flottants.
- Instants en ISO 8601 UTC (`2026-10-19T19:40:18Z`) ; dates métier en `AAAA-MM-JJ` (calendrier de Dakar).
- Énumérations en majuscules françaises (`EN_ATTENTE`, `CONFIRME`, …).
- Pagination : `?page=0&size=20&sort=champ,desc` ; réponse `{ content, page, size, totalElements, totalPages }` ;
  `size` plafonné à 100.
- Téléchargements (PDF, Excel) : corps binaire et `Content-Disposition`.

## Idempotence des opérations financières

`POST /payments/intents` et `POST /payments/manual` exigent l'en-tête `Idempotency-Key` (100 caractères maximum,
un UUID par action utilisateur). La même clé renvoie la même intention ; la même clé pour une demande différente
est refusée (409 `IDEMPOTENCE_CONFLIT`). Les webhooks sont dédoublonnés par identifiant d'événement ; la
confirmation, la génération des échéances, les pénalités et les rappels sont idempotents.

## Erreurs

Toutes les erreurs suivent le même format, sans trace technique :

```json
{
  "timestamp": "2026-10-19T19:29:41Z",
  "status": 422,
  "code": "MONTANT_TROP_ELEVE",
  "message": "Le montant dépasse le maximum autorisé (292 000 FCFA : restant dû et avance autorisée)",
  "path": "/api/v1/payments/intents",
  "fieldErrors": []
}
```

| Statut | Usage | Exemples de `code` |
|---|---|---|
| 400 | Requête mal formée ou invalide (`fieldErrors` nomme les champs) | `VALIDATION`, `REQUETE_INVALIDE`, `EN_TETE_MANQUANT`, `IDEMPOTENCE_REQUISE` |
| 401 | Non authentifié, session expirée, webhook non authentifié | `IDENTIFIANTS_INVALIDES`, `SESSION_EXPIREE`, `WEBHOOK_INVALIDE` |
| 403 | Authentifié mais non autorisé | `ACCES_REFUSE` |
| 404 | Ressource inexistante **ou appartenant à un autre membre** | `INTROUVABLE` |
| 409 | Conflit d'état, doublon, version concurrente | `ETAT_INVALIDE`, `IDEMPOTENCE_CONFLIT`, `DOUBLE_ATTRIBUTION`, `INTEGRITE` |
| 422 | Règle métier non respectée | `MONTANT_TROP_ELEVE`, `ECHEANCE_SOLDEE`, `LOT_INDISPONIBLE`, `MOT_DE_PASSE_FAIBLE` |
| 429 | Trop de tentatives | `TROP_DE_TENTATIVES` |
| 502 | Fournisseur de paiement en erreur ; la demande est conservée | `FOURNISSEUR_ERREUR`, `REMBOURSEMENT_REFUSE` |
| 503 | Fournisseur non configuré ou simulateur désactivé | `FOURNISSEUR_INDISPONIBLE`, `SIMULATION_DESACTIVEE` |

## Webhooks

`POST /api/v1/payments/webhooks/wave` : public (pas de jeton), authentifié par l'en-tête `Wave-Signature`.
Réponse 200 avec `{ "result": "CONFIRME" | "EN_VERIFICATION" | "ECHOUE" | "DEJA_RECU" | … }` ; 401 si la
signature est invalide ou trop ancienne. Voir [paiements.md](paiements.md).
