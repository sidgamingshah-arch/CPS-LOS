package com.helix.risk.repo;

import com.helix.risk.entity.CapitalResult;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface CapitalResultRepository extends JpaRepository<CapitalResult, Long> {
    Optional<CapitalResult> findFirstByApplicationReferenceOrderByCreatedAtDesc(String applicationReference);
}
