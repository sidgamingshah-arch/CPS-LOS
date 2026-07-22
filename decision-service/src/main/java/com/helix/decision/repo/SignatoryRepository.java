package com.helix.decision.repo;

import com.helix.decision.entity.Signatory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SignatoryRepository extends JpaRepository<Signatory, Long> {

    List<Signatory> findByDocumentIdOrderByIdAsc(Long documentId);
}
