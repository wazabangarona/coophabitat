package sn.coophabitat.common;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.NumberFormat;
import java.util.Locale;

/**
 * Montants en francs CFA BCEAO (XOF) : entiers, sans décimales, jamais en virgule flottante.
 */
public final class Money {

    public static final String CURRENCY = "XOF";
    public static final BigDecimal ZERO = BigDecimal.ZERO;

    private Money() {
    }

    public static BigDecimal of(long amount) {
        return BigDecimal.valueOf(amount);
    }

    /** Vérifie qu'un montant saisi est un entier de francs CFA strictement positif. */
    public static BigDecimal requirePositiveXof(BigDecimal amount, String field) {
        if (amount == null || amount.signum() <= 0) {
            throw BusinessException.badRequest("MONTANT_INVALIDE", field + " doit être strictement positif");
        }
        return requireWholeXof(amount, field);
    }

    public static BigDecimal requireWholeXof(BigDecimal amount, String field) {
        try {
            return amount.setScale(0, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException e) {
            throw BusinessException.badRequest("MONTANT_INVALIDE", field + " doit être un nombre entier de francs CFA");
        }
    }

    public static BigDecimal round(BigDecimal value, RoundingMode mode) {
        return value.setScale(0, mode);
    }

    public static BigDecimal nz(BigDecimal value) {
        return value == null ? ZERO : value;
    }

    public static BigDecimal min(BigDecimal a, BigDecimal b) {
        return a.compareTo(b) <= 0 ? a : b;
    }

    /** Format d'affichage : « 25 000 FCFA ». */
    public static String format(BigDecimal amount) {
        NumberFormat nf = NumberFormat.getIntegerInstance(Locale.FRANCE);
        nf.setGroupingUsed(true);
        return nf.format(nz(amount)).replace(' ', ' ').replace(' ', ' ') + " FCFA";
    }
}
