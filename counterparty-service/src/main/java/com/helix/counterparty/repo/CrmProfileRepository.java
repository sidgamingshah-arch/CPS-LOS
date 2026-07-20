package com.helix.counterparty.repo;

import com.helix.counterparty.entity.CrmProfile;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CrmProfileRepository extends JpaRepository<CrmProfile, Long> {

    /** Latest CRM profile ingested for a counterparty (highest id == most recent). */
    Optional<CrmProfile> findFirstByCounterpartyIdOrderByIdDesc(Long counterpartyId);

    List<CrmProfile> findByCounterpartyIdOrderByIdDesc(Long counterpartyId);
}
