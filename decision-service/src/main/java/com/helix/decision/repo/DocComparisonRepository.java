package com.helix.decision.repo;

import com.helix.decision.entity.DocComparison;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DocComparisonRepository extends JpaRepository<DocComparison, Long> {

    Optional<DocComparison> findByComparisonRef(String comparisonRef);

    boolean existsByComparisonRef(String comparisonRef);

    List<DocComparison> findBySubjectRefOrderByIdDesc(String subjectRef);
}
