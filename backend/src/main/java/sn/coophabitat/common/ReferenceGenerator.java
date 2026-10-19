package sn.coophabitat.common;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;

/**
 * Références métier uniques et lisibles, fondées sur des séquences PostgreSQL
 * (numérotation continue, sans trou volontaire).
 */
@Component
public class ReferenceGenerator {

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public ReferenceGenerator(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public String next(String prefix) {
        return String.format("%s-%d-%06d", prefix, LocalDate.now(clock).getYear(), nextVal("business_reference_seq"));
    }

    public String nextReceiptNumber() {
        return String.format("REC-%d-%06d", LocalDate.now(clock).getYear(), nextVal("receipt_number_seq"));
    }

    public String nextEntryReference() {
        return String.format("ECR-%d-%06d", LocalDate.now(clock).getYear(), nextVal("accounting_entry_seq"));
    }

    public String nextMemberNumber(int year) {
        return String.format("CH-%d-%04d", year, nextVal("member_number_seq"));
    }

    private long nextVal(String sequence) {
        Long value = jdbc.queryForObject("SELECT nextval('" + sequence + "')", Long.class);
        if (value == null) {
            throw new IllegalStateException("Séquence indisponible : " + sequence);
        }
        return value;
    }
}
