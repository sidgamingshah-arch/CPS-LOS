package com.helix.origination.repo;

import com.helix.origination.entity.SpreadVersion;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SpreadVersionRepository extends JpaRepository<SpreadVersion, Long> {

    List<SpreadVersion> findByApplicationIdOrderByVersionNoAsc(Long applicationId);

    Optional<SpreadVersion> findTopByApplicationIdOrderByVersionNoDesc(Long applicationId);

    Optional<SpreadVersion> findByApplicationIdAndVersionNo(Long applicationId, int versionNo);
}
