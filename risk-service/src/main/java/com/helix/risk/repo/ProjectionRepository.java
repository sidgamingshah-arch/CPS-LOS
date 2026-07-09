package com.helix.risk.repo;

import com.helix.risk.entity.Projection;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ProjectionRepository extends JpaRepository<Projection, Long> {
    Optional<Projection> findByApplicationReference(String applicationReference);
}
