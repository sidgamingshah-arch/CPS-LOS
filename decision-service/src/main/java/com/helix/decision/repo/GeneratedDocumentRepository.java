package com.helix.decision.repo;

import com.helix.decision.entity.GeneratedDocument;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface GeneratedDocumentRepository extends JpaRepository<GeneratedDocument, Long> {
    List<GeneratedDocument> findByApplicationReferenceOrderByIdDesc(String applicationReference);
}
