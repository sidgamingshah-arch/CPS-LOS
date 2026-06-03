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

    @GetMapping
    public List<AuditEvent> recent() {
        return auditService.recent();
    }

    @GetMapping("/subject")
    public List<AuditEvent> forSubject(@RequestParam String type, @RequestParam String id) {
        return auditService.forSubject(type, id);
    }
}
