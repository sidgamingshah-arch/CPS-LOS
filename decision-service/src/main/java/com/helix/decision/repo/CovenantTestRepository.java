package com.helix.decision.repo;

import com.helix.decision.entity.CovenantTest;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CovenantTestRepository extends JpaRepository<CovenantTest, Long> {
    List<CovenantTest> findByApplicationReferenceOrderByTestedAtDesc(String applicationReference);
}
