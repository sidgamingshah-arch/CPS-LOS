package com.helix.origination.repo;

import com.helix.origination.entity.Sublimit;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SublimitRepository extends JpaRepository<Sublimit, Long> {
    List<Sublimit> findByFacilityIdOrderByOrdinalAsc(Long facilityId);
}
