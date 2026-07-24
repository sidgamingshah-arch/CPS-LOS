package com.helix.decision.repo;

import com.helix.decision.entity.PerfectionCase;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PerfectionCaseRepository extends JpaRepository<PerfectionCase, Long> {

    Optional<PerfectionCase> findByPerfRef(String perfRef);

    List<PerfectionCase> findAllByOrderByIdDesc();

    List<PerfectionCase> findBySubjectRefOrderByIdDesc(String subjectRef);

    /** Perfection cases linked to a deal — drives the credit-proposal security/perfection section. */
    List<PerfectionCase> findByApplicationRefOrderByIdDesc(String applicationRef);

    List<PerfectionCase> findByStatusOrderByIdDesc(String status);

    /** Drives the OPTIONAL, DEFAULT-OFF limit-release gate. */
    boolean existsByApplicationRefAndStatus(String applicationRef, String status);
}
