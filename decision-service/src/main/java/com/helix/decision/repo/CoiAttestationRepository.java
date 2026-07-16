package com.helix.decision.repo;

import com.helix.decision.entity.CoiAttestation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CoiAttestationRepository extends JpaRepository<CoiAttestation, Long> {

    Optional<CoiAttestation> findByCoiRef(String coiRef);

    boolean existsByCoiRef(String coiRef);

    List<CoiAttestation> findBySubjectRefOrderByIdDesc(String subjectRef);

    List<CoiAttestation> findBySubjectRefAndStatusOrderByIdDesc(String subjectRef, String status);
}
