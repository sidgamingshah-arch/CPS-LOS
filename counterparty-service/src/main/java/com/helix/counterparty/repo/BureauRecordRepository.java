package com.helix.counterparty.repo;

import com.helix.counterparty.entity.BureauRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface BureauRecordRepository extends JpaRepository<BureauRecord, Long> {

    /** Latest bureau report ingested for a counterparty (highest id == most recent). */
    Optional<BureauRecord> findFirstByCounterpartyIdOrderByIdDesc(Long counterpartyId);

    List<BureauRecord> findByCounterpartyIdOrderByIdDesc(Long counterpartyId);
}
