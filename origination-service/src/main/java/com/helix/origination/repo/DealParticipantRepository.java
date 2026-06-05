package com.helix.origination.repo;

import com.helix.origination.entity.DealParticipant;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DealParticipantRepository extends JpaRepository<DealParticipant, Long> {
    List<DealParticipant> findByApplicationReferenceOrderByOrdinalAsc(String applicationReference);
}
