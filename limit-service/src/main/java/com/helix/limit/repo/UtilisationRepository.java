package com.helix.limit.repo;

import com.helix.limit.entity.Utilisation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface UtilisationRepository extends JpaRepository<Utilisation, Long> {
    List<Utilisation> findByLimitNodeIdOrderByCreatedAtDesc(Long limitNodeId);

    List<Utilisation> findByCifOrderByCreatedAtDesc(String cif);
}
