package com.helix.origination.repo;

import com.helix.origination.entity.SpreadCell;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SpreadCellRepository extends JpaRepository<SpreadCell, Long> {
    List<SpreadCell> findByPeriodId(Long periodId);

    List<SpreadCell> findByApplicationId(Long applicationId);
}
