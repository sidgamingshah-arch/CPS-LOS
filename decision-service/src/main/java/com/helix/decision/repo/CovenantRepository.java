package com.helix.decision.repo;

import com.helix.decision.entity.Covenant;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CovenantRepository extends JpaRepository<Covenant, Long> {
    List<Covenant> findByApplicationReference(String applicationReference);
}
