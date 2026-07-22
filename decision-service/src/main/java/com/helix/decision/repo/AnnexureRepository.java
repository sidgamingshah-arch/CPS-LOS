package com.helix.decision.repo;

import com.helix.decision.entity.Annexure;
import com.helix.decision.entity.AnnexureStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AnnexureRepository extends JpaRepository<Annexure, Long> {

    Optional<Annexure> findByAnnexureRef(String annexureRef);

    boolean existsByAnnexureRef(String annexureRef);

    List<Annexure> findBySubjectRefOrderByIdDesc(String subjectRef);

    List<Annexure> findByStatusOrderByIdDesc(AnnexureStatus status);

    List<Annexure> findByAnnexureTypeOrderByIdDesc(String annexureType);

    List<Annexure> findAllByOrderByIdDesc();
}
