# Tests et qualité

## 1. Résultats de la dernière exécution complète (19 octobre 2026)

| Suite | Commande | Résultat |
|---|---|---|
| Serveur : unitaires + intégration | `cd backend && mvn -B verify` | **77 tests, 0 échec** (BUILD SUCCESS) |
| Couverture JaCoCo (contrôle bloquant ≥ 60 % des lignes) | idem | **79,7 % des lignes**, 58,3 % des branches, 78,8 % des instructions |
| Vérificateur Python du tirage | `python3 -m unittest discover tools` | 1 test, réussi |
| Frontend : lint (TypeScript + accessibilité des gabarits) | `cd frontend && npm run lint` | 0 erreur |
| Frontend : tests unitaires (Vitest) | `cd frontend && npm test` | **13 tests, 0 échec** |
| Frontend : build de production | `npx ng build` | réussi, 376 ko initiaux (99 ko compressés) |
| Audit des dépendances npm | `npm audit --omit=dev` | 0 vulnérabilité |
| Parcours navigateur (Playwright, ordinateur + Pixel 7) | `npx playwright test` | **14/14** sur le serveur de développement ; voir aussi le rapport final pour la pile Docker |

Couverture par paquet (lignes) : accounting 94 %, admin 84 %, allocation 71 %, assembly 87 %, audit 97 %,
common 74 %, config 97 %, contribution 80 %, dashboard 99 %, demo 97 %, document 79 %, finance 84 %, jobs 0 %
(simples déclencheurs planifiés, désactivés en test ; les services qu'ils appellent sont testés), me 100 %,
member 67 %, notification 54 %, payment 80 %, payment.provider 83 %, property 75 %, publicsite 86 %, receipt 85 %,
reporting 96 %, security 76 %. Rapport détaillé : `backend/target/site/jacoco/index.html` après `mvn verify`.

## 2. Stratégie

- **Tests d'intégration sur PostgreSQL 16 réel** (Testcontainers) : migrations Flyway réelles, triggers et
  contraintes actifs. Les tables étant en ajout seul, chaque test crée ses propres données et la base est
  partagée par les classes de test (un seul démarrage de conteneur et de contexte Spring).
- **Tests unitaires** pour les calculs purs : périodes et échéances, pénalités, tirage, signatures de webhook,
  politique de mot de passe, montants en lettres, export Excel, garde-fous de production.
- **Faux serveur Wave** (serveur HTTP du JDK) pour exercer l'adaptateur Wave réel : réponses documentées, panne,
  reprise, double contrôle.
- **Parcours Playwright** sur données de démonstration fraîches, sur ordinateur et sur mobile (Pixel 7), avec
  vérification de l'absence de défilement horizontal, et **échec automatique sur toute violation de la politique
  de sécurité du contenu (CSP) ou erreur JavaScript**.

## 3. Scénarios obligatoires du cahier des charges

| Scénario | Test(s) |
|---|---|
| Connexion valide et invalide | `AuthIT.loginRefreshRotationAndReuseDetection`, `wrongPasswordAndUnknownAccountGiveTheSameAnswer`, `accountIsLockedAfterRepeatedFailures` |
| Accès interdit selon les rôles | `AuthIT.protectedEndpointsRequireAuthenticationAndPermissions`, `ApiSweepIT.bureauRolesGetTheirScreensWithoutServerErrors`, Playwright « un membre ne peut pas ouvrir l'espace de gestion » |
| Consultation du compte d'un autre membre | `PaymentIT.anotherMemberCannotSeeOrSimulateThePayment`, `ContributionIT.memberCanUpdateOnlyTheirOwnContact`, `ScenariosIT.receiptAndReportsAreExportedAsRealPdfAndExcelFiles` (reçu d'autrui : 404), `AllocationIT` (liste des candidats : 403) |
| Création et suspension d'un membre | `ScenariosIT.creationAndSuspensionOfAMemberAreTracedWithTheirReasons` |
| Cotisation mensuelle et échéance de fin de mois | `CalculatorsTest.monthlyPeriodsAreCalendarAligned`, `dueDayIsClampedToMonthEnd` |
| Paiement partiel | `ScenariosIT.partialPaymentLeavesTheRemainderDue` |
| Paiement confirmé deux fois | `PaymentIT.confirmIsIdempotent` |
| Webhook dupliqué ou invalide | `PaymentIT.duplicateWebhookCreatesASingleTransactionAndReceipt`, `anIntentIsNotAPaymentAndInvalidSignatureIsRejected`, `ScenariosIT.signedWaveSuccessIsNotCountedUntilWaveConfirmsIt`, `CommonTest.webhookSignatureAcceptsValidAndRejectsTamperedOrStale` |
| Paiement échoué | `PaymentIT.failedPaymentCreditsNothing` |
| Remboursement | `PaymentIT.manualPaymentNeedsASecondPersonAndCanBeRefunded`, `IntegrityIT.reversalIsLinkedAndHappensOnlyOnce` |
| Calcul des pénalités | `CalculatorsTest.flatPenaltyIsAppliedOnceAfterGrace`, `monthlyPercentagePenaltyUsesOutstandingAndCap`, `noPenaltyWhenNothingIsOutstanding`, `ContributionIT.penaltiesAreCrystallisedOnceAndPaidAfterTheContribution`, `penaltyRemissionRequiresAReason` |
| Génération d'un reçu | `PaymentIT.duplicateWebhookCreatesASingleTransactionAndReceipt`, `ScenariosIT.receiptAndReportsAreExportedAsRealPdfAndExcelFiles` (PDF réel téléchargé) |
| Reprise après indisponibilité du fournisseur | `ScenariosIT.providerOutageNeverCountsMoneyAndTheSameRequestResumesAfterwards` |
| Attribution d'un lot | `AllocationIT.fullProcedureProducesAVerifiableDrawExecutedOnlyOnce`, `ineligibleCandidatesAreExcludedWithAReason`, Playwright « un membre recalcule un tirage publié » |
| Tentative de double attribution | `AllocationIT.aLotCannotBeEngagedInTwoProceduresNorHaveTwoActiveResults`, `sameApproverCannotCountTwiceAndCandidatesCannotValidate` |
| Vérification de l'historique d'audit | `IntegrityIT.auditLogIsAppendOnlyAndChained`, Playwright « la présidence consulte … l'intégrité du journal » |
| Génération des rapports financiers | `FinanceIT.reportsAreFrozenAndPublishedByAnotherOfficer` |
| Export PDF et Excel | `ScenariosIT.receiptAndReportsAreExportedAsRealPdfAndExcelFiles`, `CommonTest.excelExportNeutralisesFormulas` |
| Rappel trois jours avant échéance | `ContributionIT.reminderIsSentThreeDaysBeforeDueDateOnlyOnce`, `aMissedReminderRunIsCaughtUpBeforeTheDueDate` |
| Contrôle des accès aux documents | `FinanceIT.uploadsAreCheckedByContentAndPrivateDocumentsStayPrivate` |
| Fonctionnement du frontend sur mobile | Les 7 parcours Playwright sur le profil Pixel 7, avec contrôle d'absence de défilement horizontal |

Autres invariants testés : écritures non modifiables et équilibrées (`IntegrityIT`), séparation des tâches sur
les dépenses (`FinanceIT`), relevés bancaires idempotents, transparence séparant simulé, déclaré et comptable
(`FinanceIT`), assemblées (`AssemblyIT`), avances (`ContributionIT`), plan actif figé, idempotence de la
génération, jeu de démonstration complet et marqué (`DemoDataIT`), refus des configurations dangereuses
(`ProductionSafetyCheckTest`), uniformité statistique du tirage (`DrawEngineTest.firstPositionIsRoughlyUniform`).

## 4. Lancer les tests

```bash
# Serveur (Docker requis pour Testcontainers)
cd backend && mvn -B verify

# Frontend
cd frontend && npm ci && npm run lint && npm test

# Tout sauf les parcours navigateur
scripts/verifier-tout.sh
```

**Parcours Playwright.** Ils exigent une pile démarrée avec des données de démonstration **fraîches** (le
parcours de paiement règle une échéance de `membre2` ; relancé sur la même base, il ne trouve plus d'échéance).

```bash
# Option 1 : contre la pile Docker de démonstration
COOPHABITAT_DEMO_PASSWORD='Demo-CoopHabitat-2026' scripts/demarrer-demo.sh
cd frontend && npx playwright install chromium     # une seule fois
E2E_BASE_URL=http://localhost:8080 E2E_DEMO_PASSWORD='Demo-CoopHabitat-2026' npx playwright test
# pour recommencer : docker compose -f docker-compose.yml -f docker-compose.demo.yml down -v

# Option 2 : contre le serveur de développement (section 6 du README, base vide) et ng serve
npm start &  npx playwright test
```

Variables : `E2E_BASE_URL` (défaut `http://localhost:4200`), `E2E_DEMO_PASSWORD`, `PLAYWRIGHT_CHROMIUM_PATH`
(navigateur Chromium déjà installé).

## 5. Limites

- Pas de test de charge ni de test d'intrusion.
- Les parcours Playwright couvrent les chemins principaux, pas chaque écran du bureau ; les écrans du bureau sont
  en revanche tous appelés côté API par `ApiSweepIT` pour chaque rôle.
- L'adaptateur Wave est testé contre un faux serveur conforme à la documentation publique, pas contre l'API réelle.
- La CI GitHub Actions est fournie (`.github/workflows/ci.yml`) mais n'a pas pu être exécutée sur GitHub : aucun
  dépôt distant n'a été créé. Chacune de ses commandes a été exécutée localement avec succès.
