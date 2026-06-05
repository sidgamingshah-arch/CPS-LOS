package com.helix.portfolio.repo;

import com.helix.portfolio.entity.CorrectiveAction;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;

public interface CorrectiveActionRepository extends JpaRepository<CorrectiveAction, Long> {
    List<CorrectiveAction> findByApplicationReferenceOrderByCreatedAtDesc(String applicationReference);

    List<CorrectiveAction> findByOwnerOrderByTargetDateAsc(String owner);

    List<CorrectiveAction> findByStatusOrderByTargetDateAsc(String status);

    List<CorrectiveAction> findByStatusInAndTargetDateBefore(List<String> statuses, LocalDate cutoff);
}
