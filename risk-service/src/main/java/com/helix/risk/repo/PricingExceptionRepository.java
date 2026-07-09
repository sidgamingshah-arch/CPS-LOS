package com.helix.risk.repo;

import com.helix.risk.entity.PricingException;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PricingExceptionRepository extends JpaRepository<PricingException, Long> {
    List<PricingException> findByApplicationReferenceOrderByIdDesc(String applicationReference);

    List<PricingException> findByStatusOrderByIdDesc(String status);

    List<PricingException> findByStatusInOrderByIdDesc(List<String> statuses);
}
