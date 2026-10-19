# Comptabilité, transparence et rapports

> Le plan de comptes est **simplifié et inspiré du SYSCOHADA révisé**. Il doit être validé par un
> expert-comptable avant toute utilisation réelle. L'application tient une comptabilité de gestion fiable et
> traçable ; elle ne remplace ni l'expert-comptable ni le commissaire aux comptes.

## 1. Partie double et immuabilité

- Chaque opération financière (paiement confirmé, dépense payée, remboursement, écriture manuelle) produit une
  **écriture équilibrée** d'au moins deux lignes. L'équilibre est vérifié par le service **et** par une contrainte
  différée en base : une écriture déséquilibrée ne peut pas être enregistrée, même par un accès direct à la base
  (`IntegrityIT.unbalancedEntriesAreRejectedByTheServiceAndTheDatabase`).
- Les écritures et leurs lignes sont **en ajout seul** : un trigger refuse toute modification ou suppression
  (`IntegrityIT.accountingEntriesCannotBeModifiedOrDeleted`).
- Une erreur se corrige par une **contre-écriture** liée à l'écriture d'origine, motivée, une seule fois
  (`IntegrityIT.reversalIsLinkedAndHappensOnlyOnce`). L'historique financier ne peut donc pas être modifié en
  silence.

## 2. Plan de comptes

| Compte | Libellé | Nature |
|---|---|---|
| 521100 | Banque — compte principal | Trésorerie réelle |
| 552100 | Monnaie électronique — Wave | Trésorerie réelle |
| 552200 | Monnaie électronique — SendFlash | Trésorerie réelle (inutilisé tant que SendFlash n'est pas intégré) |
| 571100 | Caisse | Trésorerie réelle |
| **559900** | Monnaie électronique — **SIMULATION (aucun fonds réel)** | **Exclu de la trésorerie** et de tous les totaux réels ; aucune écriture manuelle ni déclaration bancaire possible |
| 467100 | Membres — apports et cotisations pour programmes | Passif : fonds appartenant aux programmes des membres |
| 758100 | Produits divers — pénalités de retard | Produit |
| 222100, 231100 | Terrains ; bâtiments en cours | Actif |
| 604100 … 658100 | Fournitures, déplacements, honoraires, frais administratifs et bancaires, charges diverses | Charges |

## 3. Écritures automatiques

| Événement | Débit | Crédit |
|---|---|---|
| Paiement confirmé (Wave / espèces / virement) | 552100 / 571100 / 521100 | 467100 (cotisations), 758100 (pénalités) |
| Paiement simulé confirmé | 559900 | 467100, 758100 |
| Avance imputée sur une nouvelle échéance | écriture d'imputation tracée (source `IMPUTATION_AVANCE`) | |
| Dépense payée | compte de charge ou d'actif de la catégorie | compte de trésorerie utilisé |
| Remboursement | contre-écriture de l'écriture d'origine | |

## 4. Dépenses, budget, banque

- **Dépenses** : brouillon → soumise → approuvée (deux approbations distinctes au-delà de 500 000 FCFA) → payée,
  avec justificatif. Celui qui saisit n'approuve pas, celui qui approuve ne paie pas (`FinanceIT`).
- **Budget** prévisionnel par exercice et par catégorie, adopté puis clos ; l'écart réalisé/prévu est calculé à
  partir des écritures.
- **Banque** : le trésorier **déclare** des soldes (avec leur source : relevé, attestation, saisie) ou **importe un
  relevé CSV** (`date;libellé;montant;référence`, lignes déjà importées ignorées grâce à leur empreinte). Chaque
  ligne de relevé peut être rapprochée d'une écriture du même montant. L'écran de rapprochement montre pour chaque
  compte : solde comptable, solde déclaré, écart, lignes non rapprochées.

Un solde déclaré **n'est jamais présenté comme vérifié par la banque** : l'application n'a aucun accès direct à
un compte bancaire ni à un compte marchand.

## 5. Transparence pour les membres

La page « Transparence financière » de l'espace membre (`GET /api/v1/finance/summary`) ne montre que des
**agrégats**, chacun avec sa définition :

| Indicateur | Nature |
|---|---|
| Solde comptable de trésorerie | Calculé à partir des écritures (comptes réels seulement) |
| Solde déclaré ou importé | Déclaré par le trésorier, avec date et source ; jamais confondu avec le précédent |
| Paiements confirmés | Montant et nombre |
| Paiements en attente | Montant et nombre ; **non comptés** |
| Paiements simulés | Affichés à part, jamais additionnés aux montants réels |
| Fonds des membres détenus (467100), pénalités perçues, dépenses payées, engagées, en attente | Agrégats |
| Garde des fonds | « Fonds suivis comptablement — séquestre externe non vérifié ou non intégré » |

Aucune donnée individuelle d'un autre membre n'y figure (`FinanceIT.transparencyKeepsSimulatedDeclaredAndAccountingAmountsApart`).

**Séquestre.** Aucun mécanisme de séquestre ou de cantonnement des fonds n'existe dans l'application ni n'est
vérifié par elle. La mention ci-dessus est affichée tant que la coopérative n'a pas mis en place et documenté un
dispositif juridique et bancaire réel. Aucun écran ne permet de la remplacer : un autre texte n'est affiché que si
les champs `escrow_integrated` et `escrow_statement` des paramètres sont renseignés en base, opération à réserver
au jour où un séquestre réel et documenté existe.

## 6. Rapports

- Rapports semestriels, annuels ou sur une période : générés par le trésorier, **figés** dans un instantané au
  moment de la génération (les écritures ultérieures ne les modifient pas ; une nouvelle version doit être
  générée), PDF archivé avec empreinte, **publiés aux membres par une autre personne** (le président).
- Exports Excel : grand livre et balance sur une période, paiements, membres (formules neutralisées).
- Vérifiés par `FinanceIT.reportsAreFrozenAndPublishedByAnotherOfficer` et
  `ScenariosIT.receiptAndReportsAreExportedAsRealPdfAndExcelFiles`.

## 7. Reçus

Un reçu est émis automatiquement pour chaque paiement confirmé, à partir des données enregistrées : numéro
séquentiel, membre, montant en chiffres et en lettres, imputations, moyen de paiement, mention
« SIMULATION » le cas échéant. Il est vérifiable publiquement sur `/verification-recu` avec son numéro et son
code de vérification (comparaison en temps constant). Il porte la mention qu'il ne comporte **ni signature
manuscrite ni signature électronique cryptographique**. Un reçu émis est figé (trigger).
