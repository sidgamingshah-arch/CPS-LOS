package com.helix.decision.repo;

import com.helix.decision.entity.CovenantExtraction;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CovenantExtractionRepository extends JpaRepository<CovenantExtraction, Long> {
    List<CovenantExtraction> findByApplicationReferenceOrderByIdDesc(String applicationReference);
}
