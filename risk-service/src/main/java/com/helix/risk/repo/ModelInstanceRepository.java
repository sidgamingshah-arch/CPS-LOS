package com.helix.risk.repo;

import com.helix.risk.entity.ModelInstance;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ModelInstanceRepository extends JpaRepository<ModelInstance, Long> {
    Optional<ModelInstance> findByApplicationReference(String applicationReference);
}
