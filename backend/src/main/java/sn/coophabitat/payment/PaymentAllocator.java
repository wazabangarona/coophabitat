package sn.coophabitat.payment;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import sn.coophabitat.accounting.AccountingService;
import sn.coophabitat.accounting.AccountingService.LineSpec;
import sn.coophabitat.accounting.Accounts;
import sn.coophabitat.common.BusinessException;
import sn.coophabitat.config.ClockConfig;
import sn.coophabitat.contribution.MemberObligation;
import sn.coophabitat.contribution.MemberObligationRepository;
import sn.coophabitat.contribution.ObligationKind;
import sn.coophabitat.member.MemberRepository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Imputation des paiements sur les échéances.
 * <p>Règle d'imputation : l'échéance ciblée par le membre d'abord (si elle est ouverte), puis les échéances
 * ouvertes de la plus ancienne à la plus récente, la cotisation avant ses pénalités à date égale. Le reliquat
 * éventuel constitue une avance, imputée automatiquement sur les échéances futures dès leur génération.</p>
 * <p>Concurrence : le dossier du membre est verrouillé (SELECT … FOR UPDATE) pendant l'imputation.</p>
 */
@Service
public class PaymentAllocator {

    private final MemberRepository members;
    private final MemberObligationRepository obligations;
    private final PaymentAllocationRepository allocations;
    private final PaymentTransactionRepository transactions;
    private final AccountingService accounting;

    public PaymentAllocator(MemberRepository members, MemberObligationRepository obligations,
                            PaymentAllocationRepository allocations, PaymentTransactionRepository transactions,
                            AccountingService accounting) {
        this.members = members;
        this.obligations = obligations;
        this.allocations = allocations;
        this.transactions = transactions;
        this.accounting = accounting;
    }

    public record Outcome(BigDecimal contributions, BigDecimal penalties, BigDecimal unallocated) {
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Outcome allocate(PaymentTransaction tx, Long targetObligationId) {
        members.lockById(tx.getMemberId()).orElseThrow(() -> BusinessException.notFound("Membre"));
        BigDecimal remaining = tx.getAmount().subtract(allocations.sumForTransaction(tx.getId()));
        return allocateAmount(tx, remaining, targetObligationId);
    }

    /** Impute les avances disponibles du membre sur ses échéances ouvertes. */
    @Transactional(propagation = Propagation.MANDATORY)
    public BigDecimal applyCredit(Long memberId) {
        members.lockById(memberId).orElseThrow(() -> BusinessException.notFound("Membre"));
        BigDecimal applied = BigDecimal.ZERO;
        for (Object[] row : transactions.findUnallocated(memberId)) {
            Long txId = ((Number) row[0]).longValue();
            BigDecimal available = (BigDecimal) row[1];
            PaymentTransaction tx = transactions.findById(txId).orElseThrow();
            Outcome o = allocateAmount(tx, available, null);
            BigDecimal used = o.contributions().add(o.penalties());
            if (used.signum() == 0) {
                break;
            }
            applied = applied.add(used);
            if (o.penalties().signum() > 0) {
                // L'avance avait été comptabilisée en apports des membres : reclassement en produit de pénalité.
                accounting.post(LocalDate.now(ClockConfig.DAKAR), "Imputation d'avance sur pénalités — " + tx.getReference(),
                        "IMPUTATION_AVANCE", tx.getId(), tx.isSimulated(), List.of(
                                LineSpec.debit(Accounts.MEMBER_CONTRIBUTIONS, o.penalties(), memberId),
                                LineSpec.credit(Accounts.PENALTY_INCOME, o.penalties(), memberId)));
            }
        }
        return applied;
    }

    /** Contre-passe toutes les imputations d'une transaction (remboursement). */
    @Transactional(propagation = Propagation.MANDATORY)
    public BigDecimal reverseAll(PaymentTransaction tx) {
        members.lockById(tx.getMemberId()).orElseThrow(() -> BusinessException.notFound("Membre"));
        BigDecimal reversed = BigDecimal.ZERO;
        for (PaymentAllocation a : allocations.findByTransactionIdOrderByIdAsc(tx.getId())) {
            if (a.getReversalOfId() != null || allocations.existsByReversalOfId(a.getId())) {
                continue;
            }
            MemberObligation o = obligations.lockById(a.getObligationId()).orElseThrow();
            PaymentAllocation reversal = new PaymentAllocation();
            reversal.setTransactionId(tx.getId());
            reversal.setObligationId(o.getId());
            reversal.setAmount(a.getAmount().negate());
            reversal.setReversalOfId(a.getId());
            allocations.save(reversal);
            o.setAmountPaid(o.getAmountPaid().subtract(a.getAmount()));
            o.refreshStatus();
            reversed = reversed.add(a.getAmount());
        }
        return reversed;
    }

    private Outcome allocateAmount(PaymentTransaction tx, BigDecimal amount, Long targetObligationId) {
        List<MemberObligation> open = new ArrayList<>(obligations.lockOpenForMember(tx.getMemberId()));
        if (targetObligationId != null) {
            open.stream().filter(o -> o.getId().equals(targetObligationId)).findFirst().ifPresent(target -> {
                open.remove(target);
                open.addFirst(target);
            });
        }
        BigDecimal remaining = amount;
        BigDecimal contributions = BigDecimal.ZERO;
        BigDecimal penalties = BigDecimal.ZERO;
        for (MemberObligation o : open) {
            if (remaining.signum() <= 0) {
                break;
            }
            BigDecimal take = remaining.min(o.outstanding());
            if (take.signum() <= 0) {
                continue;
            }
            PaymentAllocation a = new PaymentAllocation();
            a.setTransactionId(tx.getId());
            a.setObligationId(o.getId());
            a.setAmount(take);
            allocations.save(a);
            o.setAmountPaid(o.getAmountPaid().add(take));
            o.refreshStatus();
            remaining = remaining.subtract(take);
            if (o.getKind() == ObligationKind.PENALITE) {
                penalties = penalties.add(take);
            } else {
                contributions = contributions.add(take);
            }
        }
        return new Outcome(contributions, penalties, remaining);
    }
}
