package com.helix.origination.repo;

import com.helix.origination.entity.DealStructure;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DealStructureRepository extends JpaRepository<DealStructure, Long> {
    Optional<DealStructure> findByApplicationReference(String applicationReference);

    List<DealStructure> findByStructureType(String structureType);
}
