package sn.coophabitat.contribution;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface MemberObligationRepository extends JpaRepository<MemberObligation, Long> {

    List<MemberObligation> findByMemberIdOrderByDueDateAscIdAsc(Long memberId);

    Page<MemberObligation> findByMemberIdOrderByDueDateDescIdDesc(Long memberId, Pageable pageable);

    /** Échéances ouvertes, dans l'ordre d'imputation : échéance la plus ancienne d'abord, cotisation avant pénalité. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select o from MemberObligation o
            where o.memberId = :memberId and o.status in (sn.coophabitat.contribution.ObligationStatus.A_PAYER,
                                                          sn.coophabitat.contribution.ObligationStatus.PARTIELLE)
            order by o.dueDate asc, o.kind asc, o.id asc
            """)
    List<MemberObligation> lockOpenForMember(Long memberId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from MemberObligation o where o.id = :id")
    Optional<MemberObligation> lockById(Long id);

    @Query("""
            select count(o) > 0 from MemberObligation o where o.memberId = :memberId and o.periodStart = :periodStart
            and o.kind = sn.coophabitat.contribution.ObligationKind.COTISATION
            and o.status <> sn.coophabitat.contribution.ObligationStatus.ANNULEE""")
    boolean existsActiveContributionForPeriod(Long memberId, LocalDate periodStart);

    boolean existsByMemberIdAndPlanIdAndPeriodStartAndKind(Long memberId, Long planId, LocalDate periodStart,
                                                          ObligationKind kind);

    List<MemberObligation> findByParentIdOrderByPenaltySequenceAsc(Long parentId);

    /** Cotisations échues non soldées au-delà de la grâce (candidates aux pénalités). */
    @Query("""
            select o from MemberObligation o
            where o.kind = sn.coophabitat.contribution.ObligationKind.COTISATION
              and o.penaltyMethod <> sn.coophabitat.contribution.PenaltyMethod.AUCUNE
              and o.status <> sn.coophabitat.contribution.ObligationStatus.ANNULEE
              and o.dueDate < :asOf
            """)
    List<MemberObligation> findPenaltyCandidates(LocalDate asOf);

    @Query("""
            select o from MemberObligation o
            where o.kind = sn.coophabitat.contribution.ObligationKind.COTISATION
              and o.dueDate between :from and :to
              and o.status in (sn.coophabitat.contribution.ObligationStatus.A_PAYER,
                               sn.coophabitat.contribution.ObligationStatus.PARTIELLE)
            order by o.dueDate, o.id
            """)
    List<MemberObligation> findOpenDueBetween(LocalDate from, LocalDate to);

    @Query("""
            select coalesce(sum(o.amountDue - o.amountPaid), 0) from MemberObligation o
            where o.memberId = :memberId and o.status in (sn.coophabitat.contribution.ObligationStatus.A_PAYER,
                                                          sn.coophabitat.contribution.ObligationStatus.PARTIELLE)
            """)
    BigDecimal outstandingForMember(Long memberId);

    @Query("""
            select coalesce(sum(o.amountDue - o.amountPaid), 0) from MemberObligation o
            where o.memberId = :memberId and o.dueDate < :asOf
              and o.status in (sn.coophabitat.contribution.ObligationStatus.A_PAYER,
                               sn.coophabitat.contribution.ObligationStatus.PARTIELLE)
            """)
    BigDecimal overdueForMember(Long memberId, LocalDate asOf);

    /** Membres ayant au moins une échéance échue non soldée à la date donnée. */
    @Query("""
            select o.memberId, sum(o.amountDue - o.amountPaid), min(o.dueDate) from MemberObligation o
            where o.dueDate < :asOf and o.status in (sn.coophabitat.contribution.ObligationStatus.A_PAYER,
                                                     sn.coophabitat.contribution.ObligationStatus.PARTIELLE)
            group by o.memberId
            """)
    List<Object[]> overdueByMember(LocalDate asOf);

    @Query("""
            select coalesce(sum(o.amountDue), 0) from MemberObligation o
            where o.kind = sn.coophabitat.contribution.ObligationKind.COTISATION
              and o.status <> sn.coophabitat.contribution.ObligationStatus.ANNULEE
              and o.dueDate between :from and :to
            """)
    BigDecimal expectedBetween(LocalDate from, LocalDate to);

    @Query("""
            select coalesce(sum(o.amountPaid), 0) from MemberObligation o
            where o.kind = sn.coophabitat.contribution.ObligationKind.COTISATION
              and o.status <> sn.coophabitat.contribution.ObligationStatus.ANNULEE
              and o.dueDate between :from and :to
            """)
    BigDecimal paidOnDueBetween(LocalDate from, LocalDate to);

    @Query("select o from MemberObligation o where o.memberId = :memberId and o.dueDate >= :from "
            + "and o.status in (sn.coophabitat.contribution.ObligationStatus.A_PAYER, "
            + "sn.coophabitat.contribution.ObligationStatus.PARTIELLE) order by o.dueDate asc")
    List<MemberObligation> upcomingForMember(Long memberId, LocalDate from, Pageable pageable);
}
