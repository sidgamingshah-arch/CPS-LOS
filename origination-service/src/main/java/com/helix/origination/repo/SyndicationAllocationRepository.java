package com.helix.origination.repo;

import com.helix.origination.entity.SyndicationAllocation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SyndicationAllocationRepository extends JpaRepository<SyndicationAllocation, Long> {
    List<SyndicationAllocation> findByApplicationReferenceOrderByIdAsc(String applicationReference);
    List<SyndicationAllocation> findByApplicationReferenceAndDrawdownRefOrderByIdAsc(
            String applicationReference, String drawdownRef);
}
