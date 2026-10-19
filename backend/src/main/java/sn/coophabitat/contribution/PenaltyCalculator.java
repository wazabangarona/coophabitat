package sn.coophabitat.contribution;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Calcul reproductible des pénalités de retard d'une cotisation.
 * <p>
 * La pénalité n° k est évaluée à la date {@code échéance + grâce + 1 jour + (k − 1) mois}, sur le principal
 * restant dû au début de ce jour (paiements imputés avant cette date). Le résultat ne dépend que de
 * données persistées : le même calcul relancé plus tard produit les mêmes montants.
 * </p>
 */
public final class PenaltyCalculator {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    /** Garde-fou : au plus 120 mois de pénalités pour une même échéance. */
    private static final int MAX_SEQUENCES = 120;

    private PenaltyCalculator() {
    }

    public record Assessment(int sequence, LocalDate assessmentDate, BigDecimal base, BigDecimal amount) {
    }

    public static LocalDate assessmentDate(LocalDate dueDate, int graceDays, int sequence) {
        return dueDate.plusDays(graceDays + 1L).plusMonths(sequence - 1L);
    }

    /**
     * @param outstandingAt principal restant dû au début du jour donné
     */
    public static List<Assessment> assess(LocalDate dueDate, int graceDays, PenaltyMethod method, BigDecimal value,
                                          BigDecimal cap, RoundingMode rounding, LocalDate asOf,
                                          Function<LocalDate, BigDecimal> outstandingAt) {
        List<Assessment> result = new ArrayList<>();
        if (method == null || method == PenaltyMethod.AUCUNE || value == null || value.signum() <= 0) {
            return result;
        }
        BigDecimal cumulative = BigDecimal.ZERO;
        for (int k = 1; k <= MAX_SEQUENCES; k++) {
            LocalDate date = assessmentDate(dueDate, graceDays, k);
            if (date.isAfter(asOf)) {
                break;
            }
            BigDecimal base = outstandingAt.apply(date);
            if (base.signum() <= 0) {
                break;
            }
            BigDecimal amount = method == PenaltyMethod.FORFAIT
                    ? value.setScale(0, rounding)
                    : base.multiply(value).divide(HUNDRED, 0, rounding);
            if (cap != null) {
                BigDecimal remaining = cap.subtract(cumulative);
                if (remaining.signum() <= 0) {
                    break;
                }
                amount = amount.min(remaining);
            }
            if (amount.signum() > 0) {
                result.add(new Assessment(k, date, base, amount));
                cumulative = cumulative.add(amount);
            }
            if (method == PenaltyMethod.FORFAIT) {
                break;
            }
        }
        return result;
    }
}
