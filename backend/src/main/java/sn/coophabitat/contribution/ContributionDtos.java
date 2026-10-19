package sn.coophabitat.contribution;

import jakarta.validation.constraints.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public final class ContributionDtos {

    private ContributionDtos() {
    }

    public record PlanRequest(@NotBlank @Size(max = 40) @Pattern(regexp = "[A-Z0-9_-]+") String code,
                              @NotBlank @Size(max = 200) String name,
                              @Size(max = 1000) String description,
                              @NotNull @Positive BigDecimal amount,
                              @NotNull Periodicity periodicity,
                              @Min(1) @Max(31) int dueDayOfMonth,
                              @NotNull LocalDate validFrom,
                              LocalDate validTo,
                              @Min(0) @Max(90) int graceDays,
                              @NotNull PenaltyMethod penaltyMethod,
                              @NotNull @PositiveOrZero BigDecimal penaltyValue,
                              @PositiveOrZero BigDecimal penaltyCap,
                              @NotNull RoundingMode roundingMode,
                              @Size(max = 300) String adoptedReference) {
    }

    public record PlanResponse(Long id, String code, String name, String description, BigDecimal amount,
                               String currency, Periodicity periodicity, int dueDayOfMonth, LocalDate validFrom,
                               LocalDate validTo, int graceDays, PenaltyMethod penaltyMethod, BigDecimal penaltyValue,
                               BigDecimal penaltyCap, RoundingMode roundingMode, PlanStatus status,
                               String adoptedReference, Instant createdAt, long memberCount) {
        public static PlanResponse of(ContributionPlan p, long memberCount) {
            return new PlanResponse(p.getId(), p.getCode(), p.getName(), p.getDescription(), p.getAmount(),
                    p.getCurrency(), p.getPeriodicity(), p.getDueDayOfMonth(), p.getValidFrom(), p.getValidTo(),
                    p.getGraceDays(), p.getPenaltyMethod(), p.getPenaltyValue(), p.getPenaltyCap(),
                    p.getRoundingMode(), p.getStatus(), p.getAdoptedReference(), p.getCreatedAt(), memberCount);
        }
    }

    public record ArchivePlanRequest(@NotNull LocalDate validTo, @NotBlank @Size(max = 1000) String reason) {
    }

    public record ObligationResponse(Long id, Long memberId, Long planId, ObligationKind kind, Long parentId,
                                     Integer penaltySequence, String periodLabel, LocalDate periodStart,
                                     LocalDate periodEnd, LocalDate dueDate, BigDecimal amountDue,
                                     BigDecimal amountPaid, BigDecimal outstanding, ObligationStatus status,
                                     boolean overdue, long daysLate, String cancelReason) {
        public static ObligationResponse of(MemberObligation o, LocalDate today) {
            boolean overdue = o.outstanding().signum() > 0 && o.getDueDate().isBefore(today);
            long late = overdue ? java.time.temporal.ChronoUnit.DAYS.between(o.getDueDate(), today) : 0;
            return new ObligationResponse(o.getId(), o.getMemberId(), o.getPlanId(), o.getKind(), o.getParentId(),
                    o.getPenaltySequence(), o.getPeriodLabel(), o.getPeriodStart(), o.getPeriodEnd(), o.getDueDate(),
                    o.getAmountDue(), o.getAmountPaid(), o.outstanding(), o.getStatus(), overdue, late,
                    o.getCancelReason());
        }
    }

    public record AdjustmentRequest(@NotNull ObligationAdjustment.Type type, @NotNull @PositiveOrZero BigDecimal newAmount,
                                    @NotBlank @Size(min = 10, max = 1000) String reason,
                                    @Size(max = 300) String decisionReference) {
    }

    public record AdjustmentResponse(Long id, Long obligationId, ObligationAdjustment.Type type,
                                     BigDecimal previousAmount, BigDecimal newAmount, String reason,
                                     String decisionReference, Long performedBy, Instant performedAt) {
        public static AdjustmentResponse of(ObligationAdjustment a) {
            return new AdjustmentResponse(a.getId(), a.getObligationId(), a.getAdjustmentType(), a.getPreviousAmount(),
                    a.getNewAmount(), a.getReason(), a.getDecisionReference(), a.getPerformedBy(), a.getPerformedAt());
        }
    }

    public record GenerationResult(LocalDate asOf, int obligationsCreated, int penaltiesCreated, int membersProcessed) {
    }

    public record PenaltySimulationRequest(@NotNull LocalDate dueDate, @NotNull @Positive BigDecimal principal,
                                           @Min(0) int graceDays, @NotNull PenaltyMethod method,
                                           @NotNull @PositiveOrZero BigDecimal value, BigDecimal cap,
                                           @NotNull RoundingMode rounding, @NotNull LocalDate asOf) {
    }

    public record PenaltySimulationResponse(List<PenaltyCalculator.Assessment> assessments, BigDecimal total) {
    }

    public record MemberFinancialSummary(Long memberId, String memberNumber, String planName, BigDecimal planAmount,
                                         BigDecimal totalDue, BigDecimal totalPaid, BigDecimal outstanding,
                                         BigDecimal overdue, BigDecimal penaltiesOutstanding, BigDecimal credit,
                                         long overdueCount, LocalDate oldestOverdueDueDate, boolean upToDate,
                                         List<ObligationResponse> upcoming, LocalDate asOf) {
    }
}
