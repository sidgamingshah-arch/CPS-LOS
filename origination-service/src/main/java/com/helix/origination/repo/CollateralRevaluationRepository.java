package com.helix.origination.repo;

import com.helix.origination.entity.CollateralRevaluation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CollateralRevaluationRepository extends JpaRepository<CollateralRevaluation, Long> {
    List<CollateralRevaluation> findByCollateralIdOrderByIdDesc(Long collateralId);

    List<CollateralRevaluation> findByApplicationReferenceOrderByIdDesc(String applicationReference);
}
