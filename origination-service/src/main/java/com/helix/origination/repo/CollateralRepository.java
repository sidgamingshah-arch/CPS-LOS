package com.helix.origination.repo;

import com.helix.origination.entity.Collateral;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CollateralRepository extends JpaRepository<Collateral, Long> {
    List<Collateral> findByApplicationId(Long applicationId);

    List<Collateral> findByFacilityId(Long facilityId);
}
