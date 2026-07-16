package com.helix.common.dms;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface StoredDocumentRepository extends JpaRepository<StoredDocument, Long> {

    List<StoredDocument> findBySubjectRefOrderByIdDesc(String subjectRef);

    List<StoredDocument> findBySubjectTypeAndSubjectRefOrderByIdDesc(String subjectType, String subjectRef);

    List<StoredDocument> findTop200ByOrderByIdDesc();
}
