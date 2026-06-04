package com.helix.decision.repo;

import com.helix.decision.entity.CadCase;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CadCaseRepository extends JpaRepository<CadCase, Long> {
    Optional<CadCase> findFirstByApplicationRefOrderByIdDesc(String applicationRef);

    List<CadCase> findByStatusOrderByCreatedAtAsc(String status);
}
