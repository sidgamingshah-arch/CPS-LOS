package com.helix.risk.repo;

import com.helix.risk.entity.RiskNote;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface RiskNoteRepository extends JpaRepository<RiskNote, Long> {

    Optional<RiskNote> findByRiskNoteRef(String riskNoteRef);

    boolean existsByRiskNoteRef(String riskNoteRef);

    List<RiskNote> findBySubjectRefOrderByIdDesc(String subjectRef);

    List<RiskNote> findAllByOrderByIdDesc();
}
