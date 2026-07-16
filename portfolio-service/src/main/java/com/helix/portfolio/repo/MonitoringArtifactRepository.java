package com.helix.portfolio.repo;

import com.helix.portfolio.entity.MonitoringArtifact;
import com.helix.portfolio.entity.MonitoringArtifactStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface MonitoringArtifactRepository extends JpaRepository<MonitoringArtifact, Long> {

    Optional<MonitoringArtifact> findByArtifactRef(String artifactRef);

    List<MonitoringArtifact> findAllByOrderByIdDesc();

    List<MonitoringArtifact> findBySubjectRefOrderByIdDesc(String subjectRef);

    List<MonitoringArtifact> findByStatusOrderByIdDesc(MonitoringArtifactStatus status);

    List<MonitoringArtifact> findByArtifactTypeOrderByIdDesc(String artifactType);
}
