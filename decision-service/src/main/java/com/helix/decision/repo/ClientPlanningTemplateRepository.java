package com.helix.decision.repo;

import com.helix.decision.entity.ClientPlanningTemplate;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ClientPlanningTemplateRepository extends JpaRepository<ClientPlanningTemplate, Long> {
    Optional<ClientPlanningTemplate> findFirstByCounterpartyReferenceOrderByVersionDesc(String counterpartyReference);

    List<ClientPlanningTemplate> findByCounterpartyReferenceOrderByVersionDesc(String counterpartyReference);
}
