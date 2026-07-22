package com.helix.origination.repo;

import com.helix.origination.entity.DocExtraction;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DocExtractionRepository extends JpaRepository<DocExtraction, Long> {
    List<DocExtraction> findByDocumentIdOrderByIdDesc(Long documentId);

    List<DocExtraction> findByApplicationReferenceAndStatusOrderByIdDesc(String applicationReference, String status);
}
