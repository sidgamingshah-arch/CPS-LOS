package com.helix.limit.repo;

import com.helix.limit.entity.LimitNode;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface LimitNodeRepository extends JpaRepository<LimitNode, Long> {
    Optional<LimitNode> findByReference(String reference);

    List<LimitNode> findByCifOrderByLevelAscOrdinalAsc(String cif);

    List<LimitNode> findByRootIdOrderByLevelAscOrdinalAsc(Long rootId);

    List<LimitNode> findByParentId(Long parentId);

    List<LimitNode> findByParentIdAndInterchangeableGroup(Long parentId, String interchangeableGroup);

    List<LimitNode> findByLevel(int level);

    List<LimitNode> findByApplicationRefOrderByLevelAscOrdinalAsc(String applicationRef);

    Optional<LimitNode> findByApplicationRefAndFacilityRef(String applicationRef, String facilityRef);
}
