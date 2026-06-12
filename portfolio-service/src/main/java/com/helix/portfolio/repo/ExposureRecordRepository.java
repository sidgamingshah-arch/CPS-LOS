package com.helix.portfolio.repo;

import com.helix.portfolio.entity.ExposureRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ExposureRecordRepository extends JpaRepository<ExposureRecord, Long> {
    Optional<ExposureRecord> findByApplicationReference(String applicationReference);

    List<ExposureRecord> findByJurisdiction(String jurisdiction);
}
