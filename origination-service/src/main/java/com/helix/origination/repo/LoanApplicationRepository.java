package com.helix.origination.repo;

import com.helix.origination.entity.LoanApplication;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface LoanApplicationRepository extends JpaRepository<LoanApplication, Long> {
    Optional<LoanApplication> findByReference(String reference);

    List<LoanApplication> findByCounterpartyRef(String counterpartyRef);
}
