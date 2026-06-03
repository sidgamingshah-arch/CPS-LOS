package com.helix.counterparty.repo;

import com.helix.counterparty.entity.ScreeningHit;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ScreeningHitRepository extends JpaRepository<ScreeningHit, Long> {
    List<ScreeningHit> findByCounterpartyIdOrderBySeverityDesc(Long counterpartyId);
}
