package com.helix.origination.repo;

import com.helix.origination.entity.FinancialPeriod;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface FinancialPeriodRepository extends JpaRepository<FinancialPeriod, Long> {
    List<FinancialPeriod> findByApplicationIdOrderByOrdinalAsc(Long applicationId);
}
