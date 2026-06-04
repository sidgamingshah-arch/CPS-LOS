package com.helix.counterparty.repo;

import com.helix.counterparty.entity.ExternalCheck;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ExternalCheckRepository extends JpaRepository<ExternalCheck, Long> {
    List<ExternalCheck> findByCounterpartyIdOrderByCheckTypeAsc(Long counterpartyId);

    Optional<ExternalCheck> findFirstByCounterpartyIdAndCheckTypeOrderByIdDesc(Long counterpartyId, String checkType);
}
