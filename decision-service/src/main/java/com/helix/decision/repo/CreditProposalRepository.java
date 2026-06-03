package com.helix.decision.repo;

import com.helix.decision.entity.CreditProposal;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CreditProposalRepository extends JpaRepository<CreditProposal, Long> {
    Optional<CreditProposal> findFirstByApplicationReferenceOrderByVersionDesc(String applicationReference);

    List<CreditProposal> findByApplicationReferenceOrderByVersionDesc(String applicationReference);
}
