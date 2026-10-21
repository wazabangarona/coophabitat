package sn.coophabitat.payment;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import sn.coophabitat.accounting.AccountingEntry;
import sn.coophabitat.accounting.AccountingEntryRepository;
import sn.coophabitat.accounting.AccountingService;
import sn.coophabitat.accounting.AccountingService.LineSpec;
import sn.coophabitat.accounting.Accounts;
import sn.coophabitat.audit.AuditCategory;
import sn.coophabitat.audit.AuditService;
import sn.coophabitat.common.BusinessException;
import sn.coophabitat.common.Hashing;
import sn.coophabitat.common.Json;
import sn.coophabitat.common.Money;
import sn.coophabitat.common.ReferenceGenerator;
import sn.coophabitat.config.AppProperties;
import sn.coophabitat.config.ClockConfig;
import sn.coophabitat.contribution.ContributionPlan;
import sn.coophabitat.contribution.ContributionPlanRepository;
import sn.coophabitat.contribution.MemberObligation;
import sn.coophabitat.contribution.MemberObligationRepository;
import sn.coophabitat.member.Member;
import sn.coophabitat.member.MemberRepository;
import sn.coophabitat.member.MemberStatus;
import sn.coophabitat.notification.NotificationService;
import sn.coophabitat.payment.PaymentDtos.CreateIntentRequest;
import sn.coophabitat.payment.PaymentDtos.ManualPaymentRequest;
import sn.coophabitat.payment.PaymentDtos.PaymentDeclarationRequest;
import sn.coophabitat.payment.provider.MockPaymentProvider;
import sn.coophabitat.payment.provider.PaymentProvider;
import sn.coophabitat.payment.provider.PaymentProvider.ProviderPaymentStatus;
import sn.coophabitat.payment.provider.PaymentProvider.State;
import sn.coophabitat.payment.provider.PaymentProviderRegistry;
import sn.coophabitat.payment.provider.WebhookSignature;
import sn.coophabitat.receipt.Receipt;
import sn.coophabitat.receipt.ReceiptService;
import sn.coophabitat.security.CurrentUser;
import sn.coophabitat.security.Permission;
import sn.coophabitat.security.PermissionResolver;
import sn.coophabitat.security.UserAccountRepository;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Cycle de vie des paiements. Principe central : une demande de paiement n'est jamais une preuve de paiement.
 * Le passage à CONFIRME n'a lieu qu'à réception d'une confirmation authentifiée, traitée de façon idempotente
 * sous verrou, avec une transaction unique par intention.
 */
@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    private final PaymentIntentRepository intents;
    private final PaymentTransactionRepository transactions;
    private final PaymentProviderEventRepository events;
    private final PaymentAllocationRepository allocations;
    private final PaymentProviderRegistry providers;
    private final PaymentAllocator allocator;
    private final MemberRepository members;
    private final MemberObligationRepository obligations;
    private final ContributionPlanRepository plans;
    private final AccountingService accounting;
    private final AccountingEntryRepository entries;
    private final ReceiptService receipts;
    private final NotificationService notifications;
    private final AuditService audit;
    private final ReferenceGenerator references;
    private final AppProperties props;
    private final UserAccountRepository users;
    private final PermissionResolver permissions;
    private final Clock clock;
    private final TransactionTemplate tx;
    private final TransactionTemplate txNew;

    public PaymentService(PaymentIntentRepository intents, PaymentTransactionRepository transactions,
                          PaymentProviderEventRepository events, PaymentAllocationRepository allocations,
                          PaymentProviderRegistry providers, PaymentAllocator allocator, MemberRepository members,
                          MemberObligationRepository obligations, ContributionPlanRepository plans,
                          AccountingService accounting, AccountingEntryRepository entries, ReceiptService receipts,
                          NotificationService notifications, AuditService audit, ReferenceGenerator references,
                          AppProperties props, UserAccountRepository users, PermissionResolver permissions,
                          Clock clock, PlatformTransactionManager tm) {
        this.intents = intents;
        this.transactions = transactions;
        this.events = events;
        this.allocations = allocations;
        this.providers = providers;
        this.allocator = allocator;
        this.members = members;
        this.obligations = obligations;
        this.plans = plans;
        this.accounting = accounting;
        this.entries = entries;
        this.receipts = receipts;
        this.notifications = notifications;
        this.audit = audit;
        this.references = references;
        this.props = props;
        this.users = users;
        this.permissions = permissions;
        this.clock = clock;
        this.tx = new TransactionTemplate(tm);
        this.txNew = new TransactionTemplate(tm);
        this.txNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    // ------------------------------------------------------------------ paiement en ligne

    /**
     * Crée (ou retrouve, par clé d'idempotence) une intention de paiement et ouvre une session chez le fournisseur.
     * En cas d'indisponibilité temporaire du fournisseur, l'intention reste INITIE : un nouvel appel avec la même
     * clé relance la création de session, sans doublon.
     */
    public PaymentIntent createIntent(Long memberId, CreateIntentRequest req, String idempotencyKey, CurrentUser user) {
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 100) {
            throw BusinessException.badRequest("IDEMPOTENCE_REQUISE", "En-tête Idempotency-Key obligatoire (100 caractères max.)");
        }
        if (req.provider() == ProviderCode.MANUEL) {
            throw BusinessException.badRequest("FOURNISSEUR_INVALIDE", "Les encaissements manuels passent par le trésorier");
        }
        BigDecimal amount = Money.requirePositiveXof(req.amount(), "Le montant");
        PaymentProvider provider = providers.get(req.provider());
        if (!provider.available()) {
            throw BusinessException.unavailable("FOURNISSEUR_INDISPONIBLE", provider.unavailableReason());
        }
        PaymentIntent intent = tx.execute(s -> {
            var existing = intents.findByMemberIdAndIdempotencyKey(memberId, idempotencyKey);
            if (existing.isPresent()) {
                PaymentIntent e = existing.get();
                if (e.getAmount().compareTo(amount) != 0 || e.getProvider() != req.provider()) {
                    throw BusinessException.conflict("IDEMPOTENCE_CONFLIT",
                            "Cette clé d'idempotence a déjà servi pour une demande différente");
                }
                return e;
            }
            validateAmount(memberId, amount, req.obligationId());
            PaymentIntent i = new PaymentIntent();
            i.setReference(references.next("PI"));
            i.setMemberId(memberId);
            i.setObligationId(req.obligationId());
            i.setAmount(amount);
            i.setProvider(req.provider());
            i.setChannel(req.provider().name());
            i.setStatus(PaymentStatus.INITIE);
            i.setIdempotencyKey(idempotencyKey);
            i.setSimulated(provider.simulated());
            i.setCreatedBy(user.userId());
            i = intents.saveAndFlush(i);
            audit.record(AuditCategory.FINANCE, "INTENTION_PAIEMENT_CREEE", "PaymentIntent", i.getId(),
                    Map.of("reference", i.getReference(), "montant", amount, "fournisseur", req.provider().name(),
                            "simule", i.isSimulated()));
            return i;
        });
        if (intent.getStatus() != PaymentStatus.INITIE) {
            return intent;
        }
        return openProviderSession(intent.getId(), provider, user);
    }

    private PaymentIntent openProviderSession(Long intentId, PaymentProvider provider, CurrentUser user) {
        PaymentIntent snapshot = intents.findById(intentId).orElseThrow();
        String phone = members.findById(snapshot.getMemberId()).map(Member::getPhone).orElse(null);
        try {
            PaymentProvider.CheckoutSession session = provider.createCheckout(new PaymentProvider.CheckoutRequest(
                    snapshot.getReference(), snapshot.getAmount(), Money.CURRENCY, phone));
            return tx.execute(s -> {
                PaymentIntent i = intents.lockById(intentId).orElseThrow();
                if (i.getStatus() == PaymentStatus.INITIE) {
                    i.setProviderSessionId(session.sessionId());
                    i.setProviderLaunchUrl(session.launchUrl());
                    i.setExpiresAt(session.expiresAt());
                    i.setStatus(PaymentStatus.EN_ATTENTE);
                    i.setFailureReason(null);
                }
                return i;
            });
        } catch (PaymentProvider.ProviderException e) {
            tx.executeWithoutResult(s -> {
                PaymentIntent i = intents.lockById(intentId).orElseThrow();
                i.setFailureReason(e.getMessage());
                if (!e.retryable()) {
                    i.setStatus(PaymentStatus.ECHOUE);
                }
                audit.record(AuditCategory.FINANCE, "FOURNISSEUR_ERREUR", "PaymentIntent", intentId,
                        Map.of("fournisseur", provider.code().name(), "reprise_possible", e.retryable()));
            });
            throw new BusinessException(HttpStatus.BAD_GATEWAY, "FOURNISSEUR_ERREUR", e.getMessage()
                    + (e.retryable() ? ". Votre demande est conservée : réessayez dans quelques instants." : "."));
        }
    }

    private void validateAmount(Long memberId, BigDecimal amount, Long obligationId) {
        Member member = members.findById(memberId).orElseThrow(() -> BusinessException.notFound("Membre"));
        if (member.getStatus() == MemberStatus.EN_ATTENTE) {
            throw BusinessException.rule("ADHESION_NON_ACTIVE", "Le dossier d'adhésion n'est pas encore activé");
        }
        BigDecimal minimum = props.payment().minimumAmount();
        if (amount.compareTo(minimum) < 0) {
            throw BusinessException.rule("MONTANT_TROP_FAIBLE", "Le montant minimum est de " + Money.format(minimum));
        }
        if (obligationId != null) {
            MemberObligation o = obligations.findById(obligationId)
                    .filter(x -> x.getMemberId().equals(memberId))
                    .orElseThrow(() -> BusinessException.notFound("Échéance"));
            if (o.outstanding().signum() <= 0) {
                throw BusinessException.rule("ECHEANCE_SOLDEE", "Cette échéance est déjà soldée");
            }
        }
        BigDecimal outstanding = obligations.outstandingForMember(memberId);
        BigDecimal maxAdvance = BigDecimal.ZERO;
        if (member.getStatus() != MemberStatus.RADIE && member.getContributionPlanId() != null) {
            ContributionPlan plan = plans.findById(member.getContributionPlanId()).orElseThrow();
            maxAdvance = plan.getAmount().multiply(BigDecimal.valueOf(props.payment().maxAdvancePeriods()));
        }
        BigDecimal max = outstanding.add(maxAdvance).subtract(transactions.creditBalance(memberId));
        if (amount.compareTo(max) > 0) {
            throw BusinessException.rule("MONTANT_TROP_ELEVE", "Le montant dépasse le maximum autorisé ("
                    + Money.format(max.max(BigDecimal.ZERO)) + " : restant dû et avance autorisée)");
        }
    }

    // ------------------------------------------------------------------ notifications fournisseur

    public PaymentDtos.WebhookResult handleWebhook(ProviderCode code, Map<String, String> headers, String rawBody) {
        PaymentProvider provider = providers.get(code);
        PaymentProvider.WebhookEvent event;
        try {
            event = provider.parseWebhook(headers, rawBody);
        } catch (PaymentProvider.InvalidWebhookException e) {
            txNew.executeWithoutResult(s -> audit.record(AuditCategory.FINANCE, "WEBHOOK_REJETE", "PaymentProviderEvent",
                    null, Map.of("fournisseur", code.name(), "motif", e.getMessage())));
            throw new BusinessException(HttpStatus.UNAUTHORIZED, "WEBHOOK_INVALIDE", "Notification non authentifiée");
        } catch (RuntimeException e) {
            throw BusinessException.badRequest("WEBHOOK_ILLISIBLE", "Notification illisible");
        }
        if (event.eventId() == null || event.eventId().isBlank()) {
            throw BusinessException.badRequest("WEBHOOK_SANS_ID", "Notification sans identifiant");
        }
        Long eventRowId;
        try {
            eventRowId = txNew.execute(s -> {
                PaymentProviderEvent e = new PaymentProviderEvent();
                e.setProvider(code);
                e.setEventId(event.eventId());
                e.setEventType(event.type());
                e.setSignatureValid(true);
                e.setPayload(sanitize(rawBody));
                return events.saveAndFlush(e).getId();
            });
        } catch (DataIntegrityViolationException duplicate) {
            return new PaymentDtos.WebhookResult("DEJA_RECU");
        }
        String result = processEvent(provider, event);
        txNew.executeWithoutResult(s -> events.findById(eventRowId).ifPresent(e -> {
            e.setProcessedAt(Instant.now(clock));
            e.setProcessingResult(result);
            findIntent(code, event).ifPresent(i -> e.setIntentId(i.getId()));
        }));
        return new PaymentDtos.WebhookResult(result);
    }

    private String processEvent(PaymentProvider provider, PaymentProvider.WebhookEvent event) {
        PaymentIntent intent = findIntent(provider.code(), event).orElse(null);
        if (intent == null) {
            log.warn("Notification {} sans intention correspondante", provider.code());
            return "INTENTION_INCONNUE";
        }
        if (event.state() == State.REUSSI) {
            String txId = event.transactionId();
            String source = provider.simulated() ? "SIMULATION" : "WEBHOOK_SIGNE";
            if (provider.supportsStatusCheck()) {
                // Double contrôle : l'état est relu auprès du fournisseur avant toute comptabilisation.
                try {
                    ProviderPaymentStatus st = provider.fetchStatus(intent.getProviderSessionId());
                    if (st.state() != State.REUSSI) {
                        markForVerification(intent.getId(), "Notification de succès non confirmée par le fournisseur");
                        return "EN_VERIFICATION";
                    }
                    txId = st.transactionId() != null ? st.transactionId() : txId;
                    source = "WEBHOOK_SIGNE+VERIFICATION_FOURNISSEUR";
                } catch (PaymentProvider.ProviderException e) {
                    markForVerification(intent.getId(), "Vérification fournisseur impossible : " + e.getMessage());
                    return "EN_VERIFICATION";
                }
            }
            confirm(intent.getId(), txId, source, null);
            return "CONFIRME";
        }
        if (event.state() == State.ECHOUE || event.state() == State.EXPIRE) {
            closeAsFailed(intent.getId(), event.state() == State.EXPIRE ? PaymentStatus.EXPIRE : PaymentStatus.ECHOUE,
                    event.detail() == null ? "Paiement refusé par le fournisseur" : event.detail());
            return event.state().name();
        }
        return "IGNORE";
    }

    private java.util.Optional<PaymentIntent> findIntent(ProviderCode code, PaymentProvider.WebhookEvent event) {
        if (event.sessionId() != null && !event.sessionId().isBlank()) {
            var bySession = intents.findByProviderAndProviderSessionId(code, event.sessionId());
            if (bySession.isPresent()) {
                return bySession;
            }
        }
        if (event.clientReference() != null && !event.clientReference().isBlank()) {
            return intents.findByReference(event.clientReference()).filter(i -> i.getProvider() == code);
        }
        return java.util.Optional.empty();
    }

    // ------------------------------------------------------------------ confirmation (idempotente)

    /**
     * Comptabilise un paiement confirmé. Idempotent : un second appel pour la même intention ne crée rien.
     * @return la transaction (existante ou nouvelle)
     */
    public PaymentTransaction confirm(Long intentId, String providerTransactionId, String source, Long verifiedBy) {
        return tx.execute(s -> {
            PaymentIntent intent = intents.lockById(intentId).orElseThrow(() -> BusinessException.notFound("Paiement"));
            var existing = transactions.findByIntentId(intentId);
            if (existing.isPresent()) {
                return existing.get();
            }
            if (intent.getStatus() == PaymentStatus.REMBOURSE) {
                throw BusinessException.conflict("DEJA_REMBOURSE", "Ce paiement a été remboursé");
            }
            Instant now = Instant.now(clock);
            PaymentTransaction t = new PaymentTransaction();
            t.setReference(references.next("PAY"));
            t.setIntentId(intent.getId());
            t.setMemberId(intent.getMemberId());
            t.setAmount(intent.getAmount());
            t.setProvider(intent.getProvider());
            t.setProviderTransactionId(blankToNull(providerTransactionId));
            t.setSimulated(intent.isSimulated());
            t.setConfirmedAt(now);
            t.setConfirmationSource(source);
            t = transactions.saveAndFlush(t);

            PaymentAllocator.Outcome outcome = allocator.allocate(t, intent.getObligationId());
            BigDecimal toContributions = t.getAmount().subtract(outcome.penalties());
            accounting.post(LocalDate.ofInstant(now, ClockConfig.DAKAR),
                    (t.isSimulated() ? "[SIMULATION] " : "") + "Encaissement " + t.getReference() + " — "
                            + intent.getChannel(), "PAIEMENT", t.getId(), t.isSimulated(), List.of(
                            LineSpec.debit(treasuryAccount(intent), t.getAmount(), t.getMemberId()),
                            LineSpec.credit(Accounts.MEMBER_CONTRIBUTIONS, toContributions, t.getMemberId()),
                            LineSpec.credit(Accounts.PENALTY_INCOME, outcome.penalties(), t.getMemberId())));

            intent.setStatus(PaymentStatus.CONFIRME);
            intent.setConfirmedAt(now);
            intent.setFailureReason(null);
            if (verifiedBy != null) {
                intent.setVerifiedBy(verifiedBy);
            }
            Receipt receipt = receipts.issue(t, obligations.outstandingForMember(t.getMemberId()));
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("reference", t.getReference());
            details.put("montant", t.getAmount());
            details.put("source", source);
            details.put("simule", t.isSimulated());
            details.put("impute_cotisations", outcome.contributions());
            details.put("impute_penalites", outcome.penalties());
            details.put("avance", outcome.unallocated());
            details.put("recu", receipt.getReceiptNumber());
            audit.record(AuditCategory.FINANCE, "PAIEMENT_CONFIRME", "PaymentTransaction", t.getId(), details);
            notifications.notifyMember(t.getMemberId(), "PAIEMENT_CONFIRME",
                    (t.isSimulated() ? "[SIMULATION] " : "") + "Paiement confirmé — reçu " + receipt.getReceiptNumber(),
                    "Votre paiement de " + Money.format(t.getAmount()) + " a été confirmé (" + intent.getChannel() + ")."
                            + (t.isSimulated() ? "\nSIMULATION — AUCUN ARGENT RÉEL TRANSFÉRÉ." : "")
                            + "\nReçu n° " + receipt.getReceiptNumber() + " disponible dans votre espace.",
                    "PAIEMENT_CONFIRME:" + t.getId(), "PaymentTransaction", t.getId());
            return t;
        });
    }

    private void markForVerification(Long intentId, String reason) {
        tx.executeWithoutResult(s -> {
            PaymentIntent i = intents.lockById(intentId).orElseThrow();
            if (i.getStatus().isOpen()) {
                i.setStatus(PaymentStatus.EN_VERIFICATION);
                i.setFailureReason(reason);
                audit.record(AuditCategory.FINANCE, "PAIEMENT_EN_VERIFICATION", "PaymentIntent", i.getId(),
                        Map.of("motif", reason));
            }
        });
    }

    private void closeAsFailed(Long intentId, PaymentStatus status, String reason) {
        tx.executeWithoutResult(s -> {
            PaymentIntent i = intents.lockById(intentId).orElseThrow();
            if (i.getStatus().isOpen()) {
                i.setStatus(status);
                i.setFailureReason(reason);
                audit.record(AuditCategory.FINANCE, "PAIEMENT_" + status.name(), "PaymentIntent", i.getId(),
                        Map.of("motif", reason));
            }
        });
    }

    // ------------------------------------------------------------------ simulation

    /**
     * Simule la réponse du fournisseur fictif en émettant une notification signée, traitée par le même circuit
     * qu'une notification réelle. SIMULATION — AUCUN ARGENT RÉEL TRANSFÉRÉ.
     */
    public PaymentIntent simulate(String reference, boolean success, CurrentUser user) {
        PaymentProvider p = providers.get(ProviderCode.MOCK);
        if (!p.available() || !(p instanceof MockPaymentProvider mock)) {
            throw BusinessException.unavailable("SIMULATION_DESACTIVEE", p.unavailableReason());
        }
        PaymentIntent intent = intents.findByReference(reference).orElseThrow(() -> BusinessException.notFound("Paiement"));
        if (intent.getProvider() != ProviderCode.MOCK || !user.isMember(intent.getMemberId())) {
            throw BusinessException.forbidden("Seul le membre concerné peut simuler son paiement");
        }
        if (intent.getStatus() != PaymentStatus.EN_ATTENTE) {
            throw BusinessException.conflict("ETAT_INVALIDE", "Ce paiement n'est plus en attente");
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", intent.getProviderSessionId());
        data.put("client_reference", intent.getReference());
        data.put("payment_status", success ? "succeeded" : "cancelled");
        data.put("transaction_id", success ? "SIM-" + Hashing.randomCode(12) : null);
        if (!success) {
            data.put("last_payment_error", Map.of("code", "simulated-failure", "message", "Échec simulé par l'utilisateur"));
        }
        String body = Json.write(Map.of("id", "evt-sim-" + UUID.randomUUID(),
                "type", success ? "checkout.session.completed" : "checkout.session.payment_failed", "data", data));
        String header = WebhookSignature.header(mock.secret(), clock.instant().getEpochSecond(), body);
        handleWebhook(ProviderCode.MOCK, Map.of("wave-signature", header), body);
        return intents.findById(intent.getId()).orElseThrow();
    }

    // ------------------------------------------------------------------ réconciliation

    /** Relit l'état d'une intention ouverte auprès du fournisseur (reprise après indisponibilité). */
    public PaymentIntent checkWithProvider(Long intentId) {
        PaymentIntent intent = intents.findById(intentId).orElseThrow(() -> BusinessException.notFound("Paiement"));
        if (!intent.getStatus().isOpen()) {
            return intent;
        }
        PaymentProvider provider = providers.get(intent.getProvider());
        if (intent.getStatus() == PaymentStatus.INITIE) {
            if (!provider.available()) {
                throw BusinessException.unavailable("FOURNISSEUR_INDISPONIBLE", provider.unavailableReason());
            }
            return openProviderSession(intentId, provider, null);
        }
        if (!provider.supportsStatusCheck() || intent.getProviderSessionId() == null) {
            expireIfDue(intent);
            return intents.findById(intentId).orElseThrow();
        }
        try {
            ProviderPaymentStatus st = provider.fetchStatus(intent.getProviderSessionId());
            tx.executeWithoutResult(s -> {
                PaymentIntent i = intents.lockById(intentId).orElseThrow();
                i.setLastCheckedAt(Instant.now(clock));
                i.setCheckAttempts(i.getCheckAttempts() + 1);
            });
            switch (st.state()) {
                case REUSSI -> confirm(intentId, st.transactionId(), "VERIFICATION_FOURNISSEUR", null);
                case ECHOUE -> closeAsFailed(intentId, PaymentStatus.ECHOUE, st.detail() == null ? "Paiement annulé" : st.detail());
                case EXPIRE -> closeAsFailed(intentId, PaymentStatus.EXPIRE, "Session expirée chez le fournisseur");
                default -> expireIfDue(intent);
            }
        } catch (PaymentProvider.ProviderException e) {
            tx.executeWithoutResult(s -> {
                PaymentIntent i = intents.lockById(intentId).orElseThrow();
                i.setLastCheckedAt(Instant.now(clock));
                i.setCheckAttempts(i.getCheckAttempts() + 1);
                i.setFailureReason("Vérification impossible : " + e.getMessage());
            });
        }
        return intents.findById(intentId).orElseThrow();
    }

    private void expireIfDue(PaymentIntent intent) {
        if (intent.getStatus() == PaymentStatus.EN_ATTENTE && intent.getExpiresAt() != null
                && intent.getExpiresAt().isBefore(Instant.now(clock))
                && !providers.get(intent.getProvider()).supportsStatusCheck()) {
            closeAsFailed(intent.getId(), PaymentStatus.EXPIRE, "Délai de paiement dépassé");
        }
    }

    /** Tâche de réconciliation : interroge le fournisseur pour les intentions ouvertes, avec espacement progressif. */
    public int reconcileOpenIntents() {
        Instant before = Instant.now(clock).minus(Duration.ofMinutes(2));
        List<PaymentIntent> open = intents.findToReconcile(EnumSet.of(PaymentStatus.EN_ATTENTE, PaymentStatus.INITIE,
                PaymentStatus.EN_VERIFICATION), EnumSet.of(ProviderCode.WAVE, ProviderCode.MOCK), before, PageRequest.of(0, 100));
        int n = 0;
        for (PaymentIntent i : open) {
            try {
                if (i.getStatus() == PaymentStatus.INITIE
                        && i.getCreatedAt().isBefore(Instant.now(clock).minus(Duration.ofHours(1)))) {
                    closeAsFailed(i.getId(), PaymentStatus.ECHOUE, "Session de paiement jamais ouverte chez le fournisseur");
                } else if (i.getStatus() != PaymentStatus.INITIE) {
                    checkWithProvider(i.getId());
                }
                n++;
            } catch (RuntimeException e) {
                log.warn("Réconciliation de l'intention {} impossible : {}", i.getReference(), e.getMessage());
            }
        }
        return n;
    }

    // ------------------------------------------------------------------ encaissements manuels

    public PaymentIntent recordManual(ManualPaymentRequest req, String idempotencyKey, CurrentUser user) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw BusinessException.badRequest("IDEMPOTENCE_REQUISE", "En-tête Idempotency-Key obligatoire");
        }
        if (!"ESPECES".equals(req.method()) && (req.externalReference() == null || req.externalReference().isBlank())) {
            throw BusinessException.badRequest("REFERENCE_REQUISE", "La référence du virement ou du chèque est obligatoire");
        }
        BigDecimal amount = Money.requirePositiveXof(req.amount(), "Le montant");
        if (req.receivedOn().isAfter(LocalDate.now(clock))) {
            throw BusinessException.badRequest("DATE_FUTURE", "La date de réception ne peut pas être future");
        }
        return tx.execute(s -> {
            var existing = intents.findByMemberIdAndIdempotencyKey(req.memberId(), idempotencyKey);
            if (existing.isPresent()) {
                return existing.get();
            }
            validateAmount(req.memberId(), amount, req.obligationId());
            PaymentIntent i = new PaymentIntent();
            i.setReference(references.next("PI"));
            i.setMemberId(req.memberId());
            i.setObligationId(req.obligationId());
            i.setAmount(amount);
            i.setProvider(ProviderCode.MANUEL);
            i.setChannel(req.method());
            i.setExternalReference(req.externalReference());
            i.setStatus(PaymentStatus.EN_VERIFICATION);
            i.setIdempotencyKey(idempotencyKey);
            i.setSimulated(false);
            i.setCreatedBy(user.userId());
            i.setFailureReason("En attente de vérification par un second responsable (reçu le " + req.receivedOn() + ")");
            i = intents.save(i);
            audit.record(AuditCategory.FINANCE, "ENCAISSEMENT_MANUEL_SAISI", "PaymentIntent", i.getId(),
                    Map.of("reference", i.getReference(), "montant", amount, "moyen", req.method(),
                            "recu_le", req.receivedOn().toString(), "commentaire", String.valueOf(req.comment())));
            return i;
        });
    }

    /**
     * Déclaration, par le membre, d'un paiement qu'il a déjà fait lui-même (Wave ou Orange Money vers le numéro de la
     * coopérative, virement ou chèque). Rien n'est compté à ce stade : la déclaration reste EN_VERIFICATION jusqu'à ce
     * qu'un responsable habilité, autre que le déclarant, retrouve l'argent sur le compte et la valide.
     */
    public PaymentIntent declare(Long memberId, PaymentDeclarationRequest req, String idempotencyKey, CurrentUser user) {
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 100) {
            throw BusinessException.badRequest("IDEMPOTENCE_REQUISE", "En-tête Idempotency-Key obligatoire (100 caractères max.)");
        }
        BigDecimal amount = Money.requirePositiveXof(req.amount(), "Le montant");
        if (req.paidOn().isAfter(LocalDate.now(clock))) {
            throw BusinessException.badRequest("DATE_FUTURE", "La date du paiement ne peut pas être future");
        }
        PaymentIntent declared = tx.execute(s -> {
            var existing = intents.findByMemberIdAndIdempotencyKey(memberId, idempotencyKey);
            if (existing.isPresent()) {
                return existing.get();
            }
            validateAmount(memberId, amount, req.obligationId());
            String reference = req.externalReference().trim();
            if (intents.existsByProviderAndChannelAndExternalReferenceAndStatusNot(ProviderCode.MANUEL, req.method(),
                    reference, PaymentStatus.ECHOUE)) {
                throw BusinessException.conflict("REFERENCE_DEJA_DECLAREE",
                        "Cette référence de transaction a déjà été déclarée ou enregistrée");
            }
            PaymentIntent i = new PaymentIntent();
            i.setReference(references.next("PI"));
            i.setMemberId(memberId);
            i.setObligationId(req.obligationId());
            i.setAmount(amount);
            i.setProvider(ProviderCode.MANUEL);
            i.setChannel(req.method());
            i.setExternalReference(reference);
            i.setStatus(PaymentStatus.EN_VERIFICATION);
            i.setIdempotencyKey(idempotencyKey);
            i.setSimulated(false);
            i.setCreatedBy(user.userId());
            i.setFailureReason("Déclaré par le membre (payé le " + req.paidOn() + ") : en attente de vérification par le bureau");
            i = intents.save(i);
            audit.record(AuditCategory.FINANCE, "PAIEMENT_DECLARE_PAR_MEMBRE", "PaymentIntent", i.getId(),
                    Map.of("reference", i.getReference(), "montant", amount, "moyen", req.method(),
                            "reference_transaction", reference, "paye_le", req.paidOn().toString(),
                            "commentaire", String.valueOf(req.comment())));
            return i;
        });
        notifyVerifiers(declared, user);
        return declared;
    }

    private void notifyVerifiers(PaymentIntent i, CurrentUser declarant) {
        List<String> roles = permissions.rolesWith(Permission.PAYMENT_MANUAL_VERIFY.name());
        if (roles.isEmpty()) {
            return;
        }
        String who = members.findById(i.getMemberId()).map(m -> m.fullName() + " (" + m.getMemberNumber() + ")").orElse("Un membre");
        for (var u : users.findActiveByRoles(roles)) {
            if (u.getId().equals(declarant.userId())) {
                continue;
            }
            notifications.notifyUser(u.getId(), "PAIEMENT_A_VERIFIER", "Paiement déclaré à vérifier",
                    who + " déclare avoir payé " + Money.format(i.getAmount()) + " (" + i.getChannel() + ", référence "
                            + i.getExternalReference() + "). Vérifiez la réception dans Paiements avant de valider.",
                    "PAIEMENT_A_VERIFIER:" + i.getId() + ":" + u.getId(), "PaymentIntent", i.getId());
        }
    }

    /** Séparation des tâches : la personne qui vérifie ne peut pas être celle qui a saisi. */
    public PaymentIntent verifyManual(Long intentId, boolean approve, String comment, CurrentUser user) {
        PaymentIntent intent = intents.findById(intentId).orElseThrow(() -> BusinessException.notFound("Paiement"));
        if (intent.getProvider() != ProviderCode.MANUEL || intent.getStatus() != PaymentStatus.EN_VERIFICATION) {
            throw BusinessException.conflict("ETAT_INVALIDE", "Ce paiement n'est pas un encaissement manuel à vérifier");
        }
        if (user.userId().equals(intent.getCreatedBy())) {
            throw BusinessException.forbidden("La vérification doit être faite par une autre personne que la saisie");
        }
        if (approve) {
            confirm(intentId, null, "VALIDATION_MANUELLE", user.userId());
        } else {
            tx.executeWithoutResult(s -> {
                PaymentIntent i = intents.lockById(intentId).orElseThrow();
                i.setStatus(PaymentStatus.ECHOUE);
                i.setVerifiedBy(user.userId());
                i.setFailureReason("Rejeté à la vérification : " + (comment == null ? "sans commentaire" : comment));
                audit.record(AuditCategory.FINANCE, "ENCAISSEMENT_MANUEL_REJETE", "PaymentIntent", intentId,
                        Map.of("commentaire", String.valueOf(comment)));
            });
        }
        return intents.findById(intentId).orElseThrow();
    }

    // ------------------------------------------------------------------ remboursement

    public PaymentTransaction refund(Long transactionId, String reason, CurrentUser user) {
        PaymentTransaction snapshot = transactions.findById(transactionId)
                .orElseThrow(() -> BusinessException.notFound("Transaction"));
        if (snapshot.getStatus() != PaymentTransaction.Status.CONFIRME) {
            throw BusinessException.conflict("DEJA_REMBOURSE", "Cette transaction a déjà été remboursée");
        }
        PaymentIntent intent = intents.findById(snapshot.getIntentId()).orElseThrow();
        PaymentProvider provider = intent.getProvider() == ProviderCode.MANUEL ? null : providers.get(intent.getProvider());
        if (provider != null) {
            if (!provider.available()) {
                throw BusinessException.unavailable("FOURNISSEUR_INDISPONIBLE", provider.unavailableReason());
            }
            try {
                provider.refund(intent.getProviderSessionId());
            } catch (PaymentProvider.ProviderException e) {
                throw new BusinessException(HttpStatus.BAD_GATEWAY, "REMBOURSEMENT_REFUSE",
                        "Le fournisseur n'a pas confirmé le remboursement : " + e.getMessage());
            }
        }
        return tx.execute(s -> {
            PaymentTransaction t = transactions.lockById(transactionId).orElseThrow();
            if (t.getStatus() != PaymentTransaction.Status.CONFIRME) {
                return t;
            }
            BigDecimal reversed = allocator.reverseAll(t);
            for (AccountingEntry e : entries.findBySourceTypeAndSourceIdOrderByIdAsc("PAIEMENT", t.getId())) {
                if (e.getReversalOfId() == null && entries.findByReversalOfId(e.getId()).isEmpty()) {
                    accounting.reverse(e.getId(), "Remboursement — " + reason);
                }
            }
            for (AccountingEntry e : entries.findBySourceTypeAndSourceIdOrderByIdAsc("IMPUTATION_AVANCE", t.getId())) {
                if (e.getReversalOfId() == null && entries.findByReversalOfId(e.getId()).isEmpty()) {
                    accounting.reverse(e.getId(), "Remboursement — " + reason);
                }
            }
            t.setStatus(PaymentTransaction.Status.REMBOURSE);
            t.setRefundedAt(Instant.now(clock));
            t.setRefundReason(reason);
            t.setRefundedBy(user.userId());
            PaymentIntent i = intents.lockById(t.getIntentId()).orElseThrow();
            i.setStatus(PaymentStatus.REMBOURSE);
            audit.record(AuditCategory.FINANCE, "PAIEMENT_REMBOURSE", "PaymentTransaction", t.getId(),
                    Map.of("reference", t.getReference(), "montant", t.getAmount(), "imputations_annulees", reversed,
                            "motif", reason, "simule", t.isSimulated()));
            notifications.notifyMember(t.getMemberId(), "PAIEMENT_REMBOURSE",
                    "Remboursement du paiement " + t.getReference(),
                    "Le paiement de " + Money.format(t.getAmount()) + " a été remboursé. Motif : " + reason,
                    "PAIEMENT_REMBOURSE:" + t.getId(), "PaymentTransaction", t.getId());
            return t;
        });
    }

    // ------------------------------------------------------------------ utilitaires

    public static String treasuryAccount(PaymentIntent intent) {
        return switch (intent.getProvider()) {
            case WAVE -> Accounts.WAVE;
            case SENDFLASH -> Accounts.SENDFLASH;
            case MOCK -> Accounts.SIMULATION;
            case MANUEL -> switch (String.valueOf(intent.getChannel())) {
                case "ESPECES" -> Accounts.CASH;
                case "WAVE" -> Accounts.WAVE;
                case "ORANGE_MONEY" -> Accounts.ORANGE_MONEY;
                default -> Accounts.BANK;
            };
        };
    }

    public void requireIntentAccess(PaymentIntent intent, CurrentUser user) {
        if (!user.isMember(intent.getMemberId()) && !user.has(Permission.PAYMENT_READ_ALL)) {
            throw BusinessException.forbidden("Accès refusé aux paiements d'un autre membre");
        }
    }

    /** Ne conserve qu'un sous-ensemble non sensible du corps de notification. */
    private static String sanitize(String rawBody) {
        try {
            var node = Json.mapper().readTree(rawBody);
            Map<String, Object> kept = new LinkedHashMap<>();
            kept.put("id", node.path("id").asString(null));
            kept.put("type", node.path("type").asString(null));
            var data = node.path("data");
            kept.put("session", data.path("id").asString(null));
            kept.put("client_reference", data.path("client_reference").asString(null));
            kept.put("payment_status", data.path("payment_status").asString(null));
            kept.put("transaction_id", data.path("transaction_id").asString(null));
            kept.put("amount", data.path("amount").asString(null));
            return Json.write(kept);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
