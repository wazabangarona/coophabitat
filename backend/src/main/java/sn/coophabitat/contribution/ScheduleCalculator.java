package sn.coophabitat.contribution;

import java.time.LocalDate;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Calcul déterministe des périodes de cotisation.
 * <ul>
 *   <li>Les périodes sont alignées sur le calendrier (mois ; trimestres janvier/avril/juillet/octobre ;
 *       semestres janvier/juillet ; année civile).</li>
 *   <li>L'échéance tombe le jour {@code dueDay} du premier mois de la période ; si ce jour n'existe pas
 *       (31 en avril, 29-31 en février), elle est ramenée au dernier jour du mois.</li>
 *   <li>Une période est due par le membre si son échéance est postérieure ou égale à la date d'effet
 *       (maximum entre la date de début du plan et la date d'adhésion).</li>
 * </ul>
 */
public final class ScheduleCalculator {

    private static final Locale FR = Locale.FRENCH;

    private ScheduleCalculator() {
    }

    public record Period(String label, LocalDate start, LocalDate end, LocalDate dueDate) {
    }

    public static LocalDate dueDate(LocalDate periodStart, int dueDay) {
        return periodStart.withDayOfMonth(Math.min(dueDay, periodStart.lengthOfMonth()));
    }

    public static LocalDate alignedStart(LocalDate date, Periodicity periodicity) {
        int k = periodicity.months();
        int month = ((date.getMonthValue() - 1) / k) * k + 1;
        return LocalDate.of(date.getYear(), month, 1);
    }

    /**
     * Périodes dont l'échéance est comprise entre {@code effectiveFrom} (inclus) et {@code dueUntil} (inclus),
     * limitées à la fin de validité du plan.
     */
    public static List<Period> periods(Periodicity periodicity, int dueDay, LocalDate effectiveFrom,
                                       LocalDate planValidTo, LocalDate dueUntil) {
        List<Period> result = new ArrayList<>();
        LocalDate start = alignedStart(effectiveFrom, periodicity);
        while (true) {
            LocalDate due = dueDate(start, dueDay);
            if (due.isAfter(dueUntil) || (planValidTo != null && start.isAfter(planValidTo))) {
                break;
            }
            if (!due.isBefore(effectiveFrom)) {
                result.add(new Period(label(start, periodicity), start,
                        start.plusMonths(periodicity.months()).minusDays(1), due));
            }
            start = start.plusMonths(periodicity.months());
        }
        return result;
    }

    public static String label(LocalDate start, Periodicity periodicity) {
        return switch (periodicity) {
            case MENSUELLE -> capitalize(start.getMonth().getDisplayName(TextStyle.FULL, FR)) + " " + start.getYear();
            case TRIMESTRIELLE -> "T" + ((start.getMonthValue() - 1) / 3 + 1) + " " + start.getYear();
            case SEMESTRIELLE -> "S" + ((start.getMonthValue() - 1) / 6 + 1) + " " + start.getYear();
            case ANNUELLE -> "Année " + start.getYear();
        };
    }

    private static String capitalize(String s) {
        return s.substring(0, 1).toUpperCase(FR) + s.substring(1);
    }
}
