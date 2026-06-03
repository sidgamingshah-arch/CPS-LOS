package com.helix.common.audit;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AuditEventRepository extends JpaRepository<AuditEvent, Long> {

    List<AuditEvent> findBySubjectTypeAndSubjectIdOrderByOccurredAtDesc(String subjectType, String subjectId);

    List<AuditEvent> findTop200ByOrderByOccurredAtDesc();
}
