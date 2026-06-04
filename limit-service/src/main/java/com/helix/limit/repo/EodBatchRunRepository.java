package com.helix.limit.repo;

import com.helix.limit.entity.EodBatchRun;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface EodBatchRunRepository extends JpaRepository<EodBatchRun, Long> {
    List<EodBatchRun> findAllByOrderByIdDesc();
}
