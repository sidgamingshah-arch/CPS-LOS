package com.helix.counterparty.repo;

import com.helix.counterparty.entity.OwnershipAssignment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface OwnershipAssignmentRepository extends JpaRepository<OwnershipAssignment, Long> {
    List<OwnershipAssignment> findByCounterpartyIdOrderByRequestedAtDesc(Long counterpartyId);
}
