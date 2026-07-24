package com.helix.counterparty.repo;

import com.helix.counterparty.entity.Counterparty;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface CounterpartyRepository extends JpaRepository<Counterparty, Long> {
    Optional<Counterparty> findByReference(String reference);

    java.util.List<Counterparty> findByGroupId(Long groupId);

    java.util.List<Counterparty> findByRecordTypeAndLifecycleStatus(String recordType, String lifecycleStatus);

    java.util.List<Counterparty> findByKycStatus(String kycStatus);

    /** Rows predating the createdBy column (null) — backfilled from the audit trail at startup. */
    java.util.List<Counterparty> findByCreatedByIsNull();
}
