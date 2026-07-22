package com.helix.decision.repo;

import com.helix.decision.entity.ExecutionPackage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ExecutionPackageRepository extends JpaRepository<ExecutionPackage, Long> {

    Optional<ExecutionPackage> findByExecRef(String execRef);

    boolean existsByExecRef(String execRef);

    List<ExecutionPackage> findBySubjectRefOrderByIdDesc(String subjectRef);

    List<ExecutionPackage> findAllByOrderByIdDesc();
}
