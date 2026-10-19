package sn.coophabitat.it;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.support.TransactionTemplate;
import sn.coophabitat.accounting.AccountingService;
import sn.coophabitat.accounting.AccountingService.LineSpec;
import sn.coophabitat.accounting.Accounts;
import sn.coophabitat.audit.AuditService;
import sn.coophabitat.common.BusinessException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Garanties portées par la base : journaux en ajout seul, écritures équilibrées, chaîne d'audit. */
class IntegrityIT extends IntegrationTest {

    @Autowired AccountingService accounting;
    @Autowired AuditService audit;
    @Autowired TransactionTemplate tx;

    private Long postEntry() {
        fx.actAs(fx.user(null, "TRESORIER"));
        return accounting.post(LocalDate.now(), "Écriture de test", "MANUELLE", null, false, List.of(
                LineSpec.debit(Accounts.CASH, BigDecimal.valueOf(1000), null),
                LineSpec.credit(Accounts.MEMBER_CONTRIBUTIONS, BigDecimal.valueOf(1000), null))).getId();
    }

    @Test
    void accountingEntriesCannotBeModifiedOrDeleted() {
        Long id = postEntry();
        assertThatThrownBy(() -> jdbc.update("UPDATE accounting_entry SET label = 'falsifié' WHERE id = ?", id))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("ajout seul");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM accounting_line WHERE entry_id = ?", id))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE accounting_line SET debit = 5 WHERE entry_id = ?", id))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void auditLogIsAppendOnlyAndChained() {
        postEntry();
        assertThatThrownBy(() -> jdbc.update("UPDATE audit_event SET action = 'x' WHERE id = (SELECT max(id) FROM audit_event)"))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM audit_event")).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.execute("TRUNCATE audit_event")).isInstanceOf(DataAccessException.class);
        assertThat(audit.verifyChain().valid()).isTrue();
    }

    @Test
    void unbalancedEntriesAreRejectedByTheServiceAndTheDatabase() {
        fx.actAs(fx.user(null, "TRESORIER"));
        assertThatThrownBy(() -> accounting.post(LocalDate.now(), "Déséquilibrée", "MANUELLE", null, false, List.of(
                LineSpec.debit(Accounts.CASH, BigDecimal.valueOf(1000), null),
                LineSpec.credit(Accounts.MEMBER_CONTRIBUTIONS, BigDecimal.valueOf(900), null))))
                .isInstanceOf(BusinessException.class);
        // Contournement direct en SQL : refusé par le trigger différé à la validation de la transaction.
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> {
            Long id = jdbc.queryForObject("""
                    INSERT INTO accounting_entry (reference, entry_date, label, source_type)
                    VALUES ('ECR-SQL-' || nextval('accounting_entry_seq'), current_date, 'SQL direct', 'MANUELLE') RETURNING id""",
                    Long.class);
            jdbc.update("INSERT INTO accounting_line (entry_id, account_code, debit, credit) VALUES (?, '571100', 500, 0)", id);
        })).isInstanceOf(DataAccessException.class);
    }

    @Test
    void reversalIsLinkedAndHappensOnlyOnce() {
        Long id = postEntry();
        var rev = accounting.reverse(id, "Erreur de compte");
        assertThat(rev.getReversalOfId()).isEqualTo(id);
        assertThatThrownBy(() -> accounting.reverse(id, "Encore")).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> accounting.reverse(rev.getId(), "Contre-passer la contre-passation"))
                .isInstanceOf(BusinessException.class);
        BigDecimal net = jdbc.queryForObject("""
                SELECT coalesce(sum(debit - credit), 0) FROM accounting_line WHERE entry_id IN (?, ?) AND account_code = '571100'""",
                BigDecimal.class, id, rev.getId());
        assertThat(net).isEqualByComparingTo("0");
    }

    @Test
    void confirmedTransactionAmountIsFrozen() {
        // La garde PostgreSQL protège les montants même en cas d'accès SQL direct.
        Long count = jdbc.queryForObject("SELECT count(*) FROM payment_transaction", Long.class);
        if (count != null && count > 0) {
            assertThatThrownBy(() -> jdbc.update("UPDATE payment_transaction SET amount = amount + 1 WHERE id = (SELECT min(id) FROM payment_transaction)"))
                    .isInstanceOf(DataAccessException.class);
        }
        assertThatThrownBy(() -> jdbc.update("DELETE FROM member")).isInstanceOf(DataAccessException.class);
    }
}
