package com.helix.config.repo;

import com.helix.config.entity.RulePack;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface RulePackRepository extends JpaRepository<RulePack, Long> {

    List<RulePack> findByJurisdictionAndTypeOrderByVersionDesc(String jurisdiction, String type);

    Optional<RulePack> findFirstByJurisdictionAndTypeAndActiveTrueOrderByVersionDesc(String jurisdiction, String type);

    boolean existsByCodeAndVersion(String code, int version);
}
