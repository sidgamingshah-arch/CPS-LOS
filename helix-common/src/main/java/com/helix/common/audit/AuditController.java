package com.helix.common.audit;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Read-only audit endpoints, automatically present in every service that includes
 * helix-common. Supports the examiner-ready trace requirement (PRD §13).
 */
@RestController
@RequestMapping("/api/audit")
public class AuditController {

    private final AuditService auditService;

    public AuditController(AuditService auditService) {
        this.auditService = auditService;
    }

    /**
     * Recent audit trail, with optional server-side filters. With no params this is the recent
     * window (unchanged). {@code actor} filters by USER NAME (contains); {@code q} is a free-text
     * COUNTERPARTY / text filter matching the summary (where names appear), subject id and actor;
     * {@code eventType}/{@code actorType}/{@code subjectType}/{@code subjectId} narrow further; and
     * {@code from}/{@code to} bound the time window.
     */
    @GetMapping
    public List<AuditEvent> recent(@RequestParam(required = false) String actor,
                                   @RequestParam(required = false) String eventType,
                                   @RequestParam(required = false) String actorType,
                                   @RequestParam(required = false) String subjectType,
                                   @RequestParam(required = false) String subjectId,
                                   @RequestParam(required = false) String q,
                                   @RequestParam(required = false) String from,
                                   @RequestParam(required = false) String to,
                                   @RequestParam(required = false) Integer limit) {
        return auditService.search(actor, eventType, actorType, subjectType, subjectId, q, from, to, limit);
    }

    @GetMapping("/subject")
    public List<AuditEvent> forSubject(@RequestParam String type, @RequestParam String id) {
        return auditService.forSubject(type, id);
    }
}
