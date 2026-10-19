# Flux de paiement

Règle fondamentale : **une demande de paiement n'est jamais une preuve de paiement.** Un montant n'est compté
dans la situation d'un membre, dans la comptabilité et sur un reçu qu'une fois l'intention à l'état `CONFIRME`.

## 1. Moyens de paiement et leur état réel

| Moyen | État | Ce qui est fait | Ce qui manque |
|---|---|---|---|
| **Wave Checkout** | Implémenté, **non testé contre l'API réelle** | Création de session, redirection vers la page officielle Wave, webhook signé, relecture de l'état, remboursement ; testé contre un faux serveur Wave reproduisant les réponses documentées, y compris panne et reprise (`ScenariosIT`) | Contrat marchand, clé API, secret de webhook, URL HTTPS publique ; essai réel de bout en bout avec de petits montants |
| **Encaissement manuel** (espèces, virement, chèque) | Opérationnel | Saisi par un responsable, confirmé par **une autre personne** ; référence de pièce obligatoire | — |
| **SendFlash** | **Indisponible** (adaptateur explicite) | L'adaptateur répond toujours « indisponible » avec la raison ; aucune requête n'est émise | Documentation technique officielle, accès d'intégration, format des webhooks, procédure de vérification d'état |
| **Simulateur** | Démonstration et tests seulement | Même chemin que Wave (webhook signé au même format, même idempotence, même imputation) ; marqué partout « SIMULATION — AUCUN ARGENT RÉEL TRANSFÉRÉ » ; comptabilisé sur le compte 559900 exclu de la trésorerie | Interdit en production : le serveur refuse de démarrer |

Aucun logo de Wave, SendFlash ou d'une banque n'est utilisé dans l'interface.

## 2. Parcours d'un paiement en ligne

Le diagramme de séquence complet est dans [architecture.md](architecture.md#5-séquence-de-paiement-wave).

1. Le membre choisit une échéance (ou une avance) et un moyen de paiement.
2. `POST /api/v1/payments/intents` avec l'en-tête **`Idempotency-Key`** (obligatoire). Le serveur vérifie :
   l'identité, que l'échéance appartient au membre et n'est pas soldée, que le montant est au moins de 100 FCFA,
   entier, et ne dépasse pas le restant dû plus 12 périodes d'avance moins le crédit existant.
3. Une intention `INITIE` est créée avec une référence unique (`PI-AAAA-NNNNNN`). La même clé d'idempotence
   renvoie la même intention ; réutilisée pour une demande différente, elle est refusée (409).
4. L'adaptateur ouvre une session chez le fournisseur : l'intention passe `EN_ATTENTE` et l'adresse de la page
   de paiement officielle est renvoyée. **Si le fournisseur est injoignable**, la réponse est 502 « demande
   conservée, réessayez » : l'intention reste `INITIE`, rien n'est compté, et le même appel la reprend.
5. Le membre paie sur la page du fournisseur. L'interface suit l'état toutes les 5 secondes et n'affiche
   « Paiement confirmé » qu'à l'état `CONFIRME`.
6. Le fournisseur envoie un webhook (`POST /api/v1/payments/webhooks/wave`) :
   - signature `Wave-Signature: t=<horodatage>,v1=<HMAC-SHA256(secret, horodatage + corps brut)>` vérifiée en temps
     constant, **horodatage à moins de 5 minutes** (anti-rejeu) ; signature invalide → 401 et événement d'audit ;
   - identifiant d'événement enregistré une seule fois (contrainte d'unicité) : un doublon répond `DEJA_RECU` ;
   - **double contrôle** : pour un succès annoncé, l'état est relu auprès de Wave (`GET /v1/checkout/sessions/{id}`).
     S'il n'est pas confirmé, l'intention passe `EN_VERIFICATION` et rien n'est compté.
7. Confirmation (idempotente, sous verrou de l'intention puis du dossier du membre) :
   - une seule `payment_transaction` par intention (contrainte d'unicité) ;
   - imputation : l'échéance ciblée d'abord, puis les plus anciennes, la cotisation avant ses pénalités ;
     le reliquat devient un **crédit d'avance** imputé automatiquement sur les échéances futures ;
   - écriture comptable équilibrée (débit trésorerie du moyen de paiement ; crédit 467100 pour les cotisations,
     758100 pour les pénalités) ;
   - reçu numéroté avec code de vérification, PDF archivé avec empreinte ; notification au membre ; audit.
8. **Réconciliation** toutes les 5 minutes : les intentions ouvertes sont vérifiées auprès du fournisseur
   (au plus une fois toutes les 2 minutes chacune) ; une session jamais ouverte depuis une heure est close en
   échec. Un webhook perdu n'empêche donc pas la confirmation, et une confirmation tardive ne crée pas de doublon.

## 3. Encaissements manuels

1. Le trésorier ou un agent saisit `POST /api/v1/payments/manual` (avec `Idempotency-Key`) : membre, montant,
   mode (`ESPECES`, `VIREMENT`, `CHEQUE`), référence de pièce, date de réception. État : `EN_VERIFICATION`.
2. Une **autre personne** disposant de `PAYMENT_MANUAL_VERIFY` (le président par défaut) confirme ou rejette
   (`POST /api/v1/payments/manual/{id}/verify`). La personne qui a saisi ne peut pas confirmer.
3. La confirmation suit exactement le même chemin que pour un paiement en ligne (imputation, écriture, reçu).

## 4. Remboursements

`POST /api/v1/payments/transactions/{id}/refund` avec un motif (permission `PAYMENT_REFUND`) :

- pour un paiement Wave, le remboursement est d'abord demandé à Wave ; **s'il n'est pas confirmé, rien n'est
  modifié** (502) ;
- les imputations sont annulées (les échéances redeviennent dues), les écritures sont **contrepassées** (jamais
  modifiées ni supprimées), la transaction passe `REMBOURSE`, le membre est notifié, l'opération est auditée ;
- une transaction ne peut être remboursée qu'une fois.

## 5. Ce qui est garanti et par quoi

| Garantie | Mécanisme | Test |
|---|---|---|
| Une intention n'est pas un paiement | Seul `CONFIRME` est compté | `PaymentIT.anIntentIsNotAPaymentAndInvalidSignatureIsRejected` |
| Pas de double comptabilisation | Unicité transaction/intention, unicité événement, verrous, confirmation idempotente | `PaymentIT.confirmIsIdempotent`, `duplicateWebhookCreatesASingleTransactionAndReceipt` |
| Webhook falsifié ou rejoué refusé | HMAC-SHA256 + fenêtre de 5 min | `CommonTest.webhookSignatureAcceptsValidAndRejectsTamperedOrStale`, `ScenariosIT` |
| Succès non confirmé par Wave non compté | Relecture de l'état | `ScenariosIT.signedWaveSuccessIsNotCountedUntilWaveConfirmsIt` |
| Panne du fournisseur sans perte ni doublon | Intention conservée, reprise par la même clé, réconciliation | `ScenariosIT.providerOutageNeverCountsMoneyAndTheSameRequestResumesAfterwards` |
| Paiement échoué : rien n'est crédité | — | `PaymentIT.failedPaymentCreditsNothing` |
| Paiement partiel | Imputation partielle, reste dû | `ScenariosIT.partialPaymentLeavesTheRemainderDue` |
| Montant d'une transaction confirmée figé | Trigger en base | `IntegrityIT.confirmedTransactionAmountIsFrozen` |
| Un membre ne voit ni ne simule le paiement d'un autre | Contrôle par ressource | `PaymentIT.anotherMemberCannotSeeOrSimulateThePayment` |
| Encaissement manuel à double contrôle, remboursable | — | `PaymentIT.manualPaymentNeedsASecondPersonAndCanBeRefunded` |
| SendFlash ne prétend jamais réussir | Adaptateur indisponible | `PaymentIT.sendflashIsUnavailableAndNeverPretendsToSucceed` |

## 6. Activer Wave en production

1. Ouvrir un compte marchand Wave Business et obtenir une **clé API Checkout** et un **secret de webhook**
   (portail marchand Wave ; documentation publique : <https://docs.wave.com/checkout> et
   <https://docs.wave.com/webhook>).
2. Déclarer l'URL de webhook : `https://<votre-domaine>/api/v1/payments/webhooks/wave`.
3. Renseigner dans `.env` : `COOPHABITAT_WAVE_ENABLED=true`, `COOPHABITAT_WAVE_API_KEY`,
   `COOPHABITAT_WAVE_WEBHOOK_SECRET`, `COOPHABITAT_WAVE_SUCCESS_URL` et `COOPHABITAT_WAVE_ERROR_URL`
   (pages de l'application, en HTTPS).
4. Redémarrer, puis vérifier `GET /api/v1/payments/providers` (Wave « Disponible »).
5. **Essai réel** avec un petit montant : paiement, webhook reçu, reçu émis, puis remboursement ; comparer avec
   le relevé du compte marchand. Ne pas ouvrir le paiement aux membres avant cet essai.
6. Rapprocher régulièrement le compte 552100 avec les relevés Wave (import CSV dans « Finances → Banque »).

## 7. Activer SendFlash

Rien n'est implémenté côté protocole, volontairement. Il faut obtenir auprès du fournisseur : la documentation
officielle de l'API (création de paiement, authentification, environnement de test), le format et la méthode de
signature des notifications, une méthode de vérification d'état et de remboursement, puis écrire un adaptateur
`PaymentProvider` sur le modèle de `WavePaymentProvider`, avec ses tests contre un faux serveur.
