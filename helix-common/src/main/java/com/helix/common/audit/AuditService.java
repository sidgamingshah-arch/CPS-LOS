package com.helix.common.audit;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * Writes immutable audit events. Insert-only by design (PRD §9, §11 governance):
 * there is deliberately no update or delete path.
 */
@Service
public class AuditService {

    private final AuditEventRepository repository;
    private final String serviceName;

    public AuditService(AuditEventRepository repository,
                        @Value("${spring.application.name:helix-service}") String serviceName) {
        this.repository = repository;
        this.serviceName = serviceName;
    }

    /** Record an action performed by a named human actor. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public AuditEvent human(String actor, String eventType, String subjectType, String subjectId,
                            String summary, Map<String, Object> detail) {
        return write(actor, "HUMAN", eventType, subjectType, subjectId, summary, detail);
    }

    /** Record an action performed by an AI capability (governed, never credit-binding). */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public AuditEvent ai(String capability, String eventType, String subjectType, String subjectId,
                         String summary, Map<String, Object> detail) {
        return write(capability, "AI", eventType, subjectType, subjectId, summary, detail);
    }

    /** Record a deterministic action performed by the engine (capital/ECL computation). */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public AuditEvent engine(String eventType, String subjectType, String subjectId,
                             String summary, Map<String, Object> detail) {
        return write("engine", "SYSTEM", eventType, subjectType, subjectId, summary, detail);
    }

    private AuditEvent write(String actor, String actorType, String eventType, String subjectType,
                             String subjectId, String summary, Map<String, Object> detail) {
        AuditEvent event = new AuditEvent();
        event.setService(serviceName);
        event.setActor(actor);
        event.setActorType(actorType);
        event.setEventType(eventType);
        event.setSubjectType(subjectType);
        event.setSubjectId(subjectId);
        event.setSummary(summary);
        event.setDetail(detail == null ? Map.of() : detail);
        return repository.save(event);
    }

    @Transactional(readOnly = true)
    public List<AuditEvent> forSubject(String subjectType, String subjectId) {
        return repository.findBySubjectTypeAndSubjectIdOrderByOccurredAtDesc(subjectType, subjectId);
    }

    @Transactional(readOnly = true)
    public List<AuditEvent> recent() {
        return repository.findTop200ByOrderByOccurredAtDesc();
    }
}
