package com.helix.decision.repo;

import com.helix.decision.entity.DocumentExecution;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DocumentExecutionRepository extends JpaRepository<DocumentExecution, Long> {

    List<DocumentExecution> findByExecRefOrderByIdAsc(String execRef);
}
