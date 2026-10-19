package sn.coophabitat.finance;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface FinancialReportRepository extends JpaRepository<FinancialReport, Long> {

    List<FinancialReport> findAllByOrderByPeriodEndDescIdDesc();

    List<FinancialReport> findByStatusOrderByPeriodEndDescIdDesc(FinancialReport.Status status);
}
