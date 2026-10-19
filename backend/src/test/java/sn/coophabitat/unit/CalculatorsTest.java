package sn.coophabitat.unit;

import org.junit.jupiter.api.Test;
import sn.coophabitat.contribution.PenaltyCalculator;
import sn.coophabitat.contribution.PenaltyMethod;
import sn.coophabitat.contribution.Periodicity;
import sn.coophabitat.contribution.ScheduleCalculator;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CalculatorsTest {

    @Test
    void dueDayIsClampedToMonthEnd() {
        assertThat(ScheduleCalculator.dueDate(LocalDate.of(2026, 2, 1), 31)).isEqualTo(LocalDate.of(2026, 2, 28));
        assertThat(ScheduleCalculator.dueDate(LocalDate.of(2028, 2, 1), 30)).isEqualTo(LocalDate.of(2028, 2, 29));
    }

    @Test
    void monthlyPeriodsAreCalendarAligned() {
        var periods = ScheduleCalculator.periods(Periodicity.MENSUELLE, 5, LocalDate.of(2026, 1, 10), null,
                LocalDate.of(2026, 4, 30));
        // Janvier : échéance du 5 antérieure à l'entrée en vigueur (10) → exclue.
        assertThat(periods).extracting(ScheduleCalculator.Period::dueDate)
                .containsExactly(LocalDate.of(2026, 2, 5), LocalDate.of(2026, 3, 5), LocalDate.of(2026, 4, 5));
        assertThat(periods.get(0).label()).isEqualTo("Février 2026");
    }

    @Test
    void quarterlyPeriodsAndLabels() {
        var periods = ScheduleCalculator.periods(Periodicity.TRIMESTRIELLE, 15, LocalDate.of(2026, 1, 1), null,
                LocalDate.of(2026, 12, 31));
        assertThat(periods).hasSize(4);
        assertThat(periods.get(3).label()).isEqualTo("T4 2026");
        assertThat(periods.get(3).end()).isEqualTo(LocalDate.of(2026, 12, 31));
    }

    @Test
    void periodsStopAtPlanEnd() {
        var periods = ScheduleCalculator.periods(Periodicity.MENSUELLE, 5, LocalDate.of(2026, 1, 1),
                LocalDate.of(2026, 2, 28), LocalDate.of(2026, 12, 31));
        assertThat(periods).hasSize(2);
    }

    @Test
    void flatPenaltyIsAppliedOnceAfterGrace() {
        LocalDate due = LocalDate.of(2026, 3, 5);
        assertThat(PenaltyCalculator.assess(due, 5, PenaltyMethod.FORFAIT, BigDecimal.valueOf(1000), null,
                RoundingMode.HALF_UP, LocalDate.of(2026, 3, 10), d -> BigDecimal.valueOf(25000))).isEmpty();
        var a = PenaltyCalculator.assess(due, 5, PenaltyMethod.FORFAIT, BigDecimal.valueOf(1000), null,
                RoundingMode.HALF_UP, LocalDate.of(2026, 6, 30), d -> BigDecimal.valueOf(25000));
        assertThat(a).hasSize(1);
        assertThat(a.get(0).assessmentDate()).isEqualTo(LocalDate.of(2026, 3, 11));
        assertThat(a.get(0).amount()).isEqualByComparingTo("1000");
    }

    @Test
    void monthlyPercentagePenaltyUsesOutstandingAndCap() {
        LocalDate due = LocalDate.of(2026, 1, 5);
        List<PenaltyCalculator.Assessment> a = PenaltyCalculator.assess(due, 0, PenaltyMethod.POURCENTAGE_MENSUEL,
                new BigDecimal("2"), BigDecimal.valueOf(1200), RoundingMode.HALF_UP, LocalDate.of(2026, 6, 30),
                d -> d.isBefore(LocalDate.of(2026, 3, 1)) ? BigDecimal.valueOf(25000) : BigDecimal.valueOf(10000));
        // 2 % de 25 000 = 500 (06/01, 06/02) puis 2 % de 10 000 = 200 jusqu'au plafond de 1 200.
        assertThat(a).extracting(PenaltyCalculator.Assessment::amount)
                .usingElementComparator(BigDecimal::compareTo)
                .containsExactly(BigDecimal.valueOf(500), BigDecimal.valueOf(500), BigDecimal.valueOf(200));
    }

    @Test
    void noPenaltyWhenNothingIsOutstanding() {
        assertThat(PenaltyCalculator.assess(LocalDate.of(2026, 1, 5), 0, PenaltyMethod.POURCENTAGE_MENSUEL,
                BigDecimal.ONE, null, RoundingMode.HALF_UP, LocalDate.of(2026, 6, 1), d -> BigDecimal.ZERO)).isEmpty();
        assertThat(PenaltyCalculator.assess(LocalDate.of(2026, 1, 5), 0, PenaltyMethod.AUCUNE,
                BigDecimal.ONE, null, RoundingMode.HALF_UP, LocalDate.of(2026, 6, 1), d -> BigDecimal.TEN)).isEmpty();
    }
}
