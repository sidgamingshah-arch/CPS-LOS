package com.helix.decision.repo;

import com.helix.decision.entity.CovenantAction;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CovenantActionRepository extends JpaRepository<CovenantAction, Long> {
    List<CovenantAction> findByScheduleIdOrderByRequestedAtDesc(Long scheduleId);
}
