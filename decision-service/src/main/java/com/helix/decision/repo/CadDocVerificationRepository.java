package com.helix.decision.repo;

import com.helix.decision.entity.CadDocVerification;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CadDocVerificationRepository extends JpaRepository<CadDocVerification, Long> {
    List<CadDocVerification> findByCadCaseIdOrderByIdDesc(Long cadCaseId);

    List<CadDocVerification> findByChecklistItemIdOrderByIdDesc(Long checklistItemId);
}
