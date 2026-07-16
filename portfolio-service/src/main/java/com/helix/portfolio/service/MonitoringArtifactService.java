package com.helix.portfolio.service;

import com.helix.common.audit.AuditService;
import com.helix.common.query.QueryService;
import com.helix.common.rbac.ActorDirectory;
import com.helix.common.web.ApiException;
import com.helix.portfolio.client.MonitoringArtifactMasterClient;
import com.helix.portfolio.client.MonitoringArtifactMasterClient.ArtifactTypeSpec;
import com.helix.portfolio.client.MonitoringArtifactMasterClient.Section;
import com.helix.portfolio.entity.MonitoringArtifact;
import com.helix.portfolio.entity.MonitoringArtifactStatus;
import com.helix.portfolio.repo.MonitoringArtifactRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The monitoring-artifact engine. ONE governed lifecycle for every monitoring
 * artifact type (call memo, plant visit, LCR, QPR, broker review, stock audit,
 * audit note), driven by the {@code MONITORING_ARTIFACT_TYPE} master.
 *
 * <p><b>Governance</b>: every write stamps an audit event; the review / approve /
 * authorize gates are role-gated AND name-equality SoD'd (reviewer ≠ owner,
 * approver ≠ reviewer, authoriser ≠ approver) — a violation is a
 * {@code forbiddenAutonomy} 403. The stock-audit vendor-RFQ lane raises an
 * EXTERNAL_VENDOR {@link QueryService} thread (a human picks the vendor from
 * {@code VENDOR_MASTER}); the vendor reply arrives via the Query module's
 * {@code external-response} callback.</p>
 *
 * <p><b>Record / advisory invariant</b>: this engine NEVER mutates an authoritative
 * figure (ECL / IRAC / exposure). It only reads the artifact-type master and writes
 * its own {@code monitoring_artifacts} rows + audit + a query thread.</p>
 */
@Service
public class MonitoringArtifactService {

    private static final Logger log = LoggerFactory.getLogger(MonitoringArtifactService.class);
    private static final String SUBJECT = "MonitoringArtifact";

    // Role gates (any-of), on top of name-equality SoD. Reuse the platform's ACTOR_ROLE roles.
    private static final Set<String> REVIEW_ROLES =
            Set.of("CREDIT_OFFICER", "CREDIT_OPS", "PORTFOLIO", "CREDIT_HEAD", "RM_HEAD", "CREDIT_COMMITTEE", "CRO");
    private static final Set<String> APPROVE_ROLES =
            Set.of("CREDIT_OFFICER", "CREDIT_HEAD", "RM_HEAD", "CREDIT_COMMITTEE", "CRO");
    private static final Set<String> AUTHORIZE_ROLES =
            Set.of("CREDIT_COMMITTEE", "CRO", "BOARD_COMMITTEE");

    private final MonitoringArtifactRepository repo;
    private final MonitoringArtifactMasterClient masters;
    private final AuditService audit;
    private final QueryService queries;
    private final ObjectProvider<ActorDirectory> directory;

    public MonitoringArtifactService(MonitoringArtifactRepository repo, MonitoringArtifactMasterClient masters,
                                     AuditService audit, QueryService queries,
                                     ObjectProvider<ActorDirectory> directory) {
        this.repo = repo;
        this.masters = masters;
        this.audit = audit;
        this.queries = queries;
        this.directory = directory;
    }

    /** Artifact + (optionally) the linked vendor-RFQ query status — the read model for one artifact. */
    public record View(MonitoringArtifact artifact, QueryService.View vendorQuery) {
    }

    @Transactional
    public MonitoringArtifact create(String artifactType, String subjectType, String subjectRef,
                                     String title, String owner) {
        if (artifactType == null || artifactType.isBlank()) {
            throw ApiException.badRequest("artifactType is required (a MONITORING_ARTIFACT_TYPE master key)");
        }
        if (owner == null || owner.isBlank()) {
            throw ApiException.badRequest("owner (X-Actor) is required");
        }
        ArtifactTypeSpec spec = masters.artifactType(artifactType.trim());

        MonitoringArtifact a = new MonitoringArtifact();
        a.setArtifactRef(newRef());
        a.setArtifactType(artifactType.trim());
        a.setSubjectType(subjectType);
        a.setSubjectRef(subjectRef);
        a.setTitle(title == null || title.isBlank() ? artifactType.trim() : title.trim());
        a.setSections(materialiseSections(spec.sections()));
        a.setMasterVersion(spec.version());
        a.setRequiresAuthorize(spec.requiresAuthorize());
        a.setVendorRfq(spec.vendorRfq());
        a.setStatus(MonitoringArtifactStatus.DRAFT);
        a.setOwner(owner);
        MonitoringArtifact saved = repo.save(a);

        audit.human(owner, "MON_ARTIFACT_CREATED", SUBJECT, saved.getArtifactRef(),
                "Created %s '%s' on %s (master v%d)".formatted(saved.getArtifactType(), saved.getTitle(),
                        safe(subjectRef), spec.version()),
                Map.of("artifactType", saved.getArtifactType(), "subjectRef", safe(subjectRef),
                        "masterVersion", spec.version(), "requiresAuthorize", spec.requiresAuthorize(),
                        "vendorRfq", spec.vendorRfq()));
        return saved;
    }

    @Transactional
    public MonitoringArtifact updateSections(String ref, Map<String, Object> sectionUpdates, String actor) {
        MonitoringArtifact a = require(ref);
        requireOwner(a, actor, "edit sections");
        if (a.getStatus() != MonitoringArtifactStatus.DRAFT) {
            throw ApiException.conflict("Artifact " + ref + " is " + a.getStatus() + " — sections are locked");
        }
        Map<String, Object> merged = a.getSections() == null
                ? new LinkedHashMap<>() : new LinkedHashMap<>(a.getSections());
        if (sectionUpdates != null) merged.putAll(sectionUpdates);
        a.setSections(merged);
        MonitoringArtifact saved = repo.save(a);
        audit.human(actor, "MON_ARTIFACT_EDITED", SUBJECT, ref, "Sections edited on " + ref,
                Map.of("sections", merged.keySet()));
        return saved;
    }

    @Transactional
    public MonitoringArtifact submit(String ref, String actor) {
        MonitoringArtifact a = require(ref);
        requireOwner(a, actor, "submit");
        if (a.getStatus() != MonitoringArtifactStatus.DRAFT) {
            throw ApiException.conflict("Only a DRAFT can be submitted; " + ref + " is " + a.getStatus());
        }
        a.setStatus(MonitoringArtifactStatus.SUBMITTED);
        a.setSubmittedAt(Instant.now());
        MonitoringArtifact saved = repo.save(a);
        audit.human(actor, "MON_ARTIFACT_SUBMITTED", SUBJECT, ref, "Artifact " + ref + " submitted for review",
                Map.of("artifactType", a.getArtifactType()));
        return saved;
    }

    @Transactional
    public MonitoringArtifact review(String ref, String notes, String actor) {
        MonitoringArtifact a = require(ref);
        if (a.getStatus() != MonitoringArtifactStatus.SUBMITTED) {
            throw ApiException.conflict("Only a SUBMITTED artifact can be reviewed; " + ref + " is " + a.getStatus());
        }
        // SoD first (so "review by owner -> 403" is unambiguously a segregation-of-duties block),
        // then the role gate.
        if (actor.equalsIgnoreCase(a.getOwner())) {
            throw ApiException.forbiddenAutonomy(
                    "Reviewer must differ from the owner ('" + a.getOwner() + "') — segregation of duties");
        }
        requireRole(actor, REVIEW_ROLES, "review monitoring artifacts");
        a.setStatus(MonitoringArtifactStatus.REVIEWED);
        a.setReviewer(actor);
        a.setReviewedBy(actor);
        a.setReviewedAt(Instant.now());
        a.setReviewNotes(notes);
        MonitoringArtifact saved = repo.save(a);
        audit.human(actor, "MON_ARTIFACT_REVIEWED", SUBJECT, ref, "Artifact " + ref + " reviewed by " + actor,
                Map.of("reviewer", actor, "notes", safe(notes)));
        return saved;
    }

    @Transactional
    public MonitoringArtifact approve(String ref, String notes, String actor) {
        MonitoringArtifact a = require(ref);
        if (a.getStatus() != MonitoringArtifactStatus.REVIEWED) {
            throw ApiException.conflict("Only a REVIEWED artifact can be approved; " + ref + " is " + a.getStatus());
        }
        if (actor.equalsIgnoreCase(a.getReviewer())) {
            throw ApiException.forbiddenAutonomy(
                    "Approver must differ from the reviewer ('" + a.getReviewer() + "') — segregation of duties");
        }
        requireRole(actor, APPROVE_ROLES, "approve monitoring artifacts");
        a.setStatus(MonitoringArtifactStatus.APPROVED);
        a.setApprover(actor);
        a.setApprovedBy(actor);
        a.setApprovedAt(Instant.now());
        a.setApprovalNotes(notes);
        MonitoringArtifact saved = repo.save(a);
        audit.human(actor, "MON_ARTIFACT_APPROVED", SUBJECT, ref, "Artifact " + ref + " approved by " + actor,
                Map.of("approver", actor, "notes", safe(notes)));
        return saved;
    }

    @Transactional
    public MonitoringArtifact authorize(String ref, String notes, String actor) {
        MonitoringArtifact a = require(ref);
        if (!a.isRequiresAuthorize()) {
            throw ApiException.conflict("Artifact type " + a.getArtifactType() + " does not require authorisation");
        }
        if (a.getStatus() != MonitoringArtifactStatus.APPROVED) {
            throw ApiException.conflict("Only an APPROVED artifact can be authorised; " + ref + " is " + a.getStatus());
        }
        if (actor.equalsIgnoreCase(a.getApprover())) {
            throw ApiException.forbiddenAutonomy(
                    "Authoriser must differ from the approver ('" + a.getApprover() + "') — segregation of duties");
        }
        requireRole(actor, AUTHORIZE_ROLES, "authorise monitoring artifacts");
        a.setStatus(MonitoringArtifactStatus.AUTHORIZED);
        a.setAuthorisedBy(actor);
        a.setAuthorisedAt(Instant.now());
        a.setAuthorisationNotes(notes);
        MonitoringArtifact saved = repo.save(a);
        audit.human(actor, "MON_ARTIFACT_AUTHORIZED", SUBJECT, ref, "Artifact " + ref + " authorised by " + actor,
                Map.of("authorisedBy", actor, "notes", safe(notes)));
        return saved;
    }

    /**
     * Stock-audit vendor RFQ (only for types whose master carries {@code vendorRfq=true}).
     * A human supplies the chosen vendor id (a VENDOR_MASTER recordKey — never auto-selected);
     * we raise an EXTERNAL_VENDOR query thread through the Query module (façade — no real
     * transport) and pin the returned queryRef on the artifact. The vendor reply arrives via
     * the Query module's {@code external-response} callback.
     */
    @Transactional
    public MonitoringArtifact vendorRfq(String ref, String vendorId, String question, String actor) {
        MonitoringArtifact a = require(ref);
        if (!a.isVendorRfq()) {
            throw ApiException.conflict("Artifact type " + a.getArtifactType() + " does not support a vendor RFQ");
        }
        if (vendorId == null || vendorId.isBlank()) {
            throw ApiException.badRequest("vendorId is required — a human must choose the vendor from VENDOR_MASTER");
        }
        List<String> vendors = masters.vendorKeys();
        if (!vendors.isEmpty() && !vendors.contains(vendorId)) {
            throw ApiException.badRequest("Vendor '" + vendorId + "' is not in VENDOR_MASTER " + vendors);
        }
        String q = (question == null || question.isBlank())
                ? "Please provide the stock-audit report for " + safe(a.getSubjectRef()) + "."
                : question.trim();
        QueryService.Raise cmd = new QueryService.Raise("EXTERNAL_VENDOR", SUBJECT, a.getArtifactRef(),
                "Stock-audit RFQ: " + a.getTitle(), q, vendorId, "vendor",
                null, null, null, null, null, List.of("vendor"), null);
        QueryService.View qv = queries.raise(cmd, actor);
        a.setVendorRef(vendorId);
        a.setVendorQueryRef(qv.thread().getQueryRef());
        MonitoringArtifact saved = repo.save(a);
        audit.human(actor, "MON_ARTIFACT_VENDOR_RFQ", SUBJECT, ref,
                "Vendor RFQ raised to '" + vendorId + "' (query " + qv.thread().getQueryRef() + ")",
                Map.of("vendorRef", vendorId, "queryRef", qv.thread().getQueryRef()));
        return saved;
    }

    @Transactional(readOnly = true)
    public View view(String ref) {
        MonitoringArtifact a = require(ref);
        QueryService.View vendorQuery = null;
        if (a.getVendorQueryRef() != null && !a.getVendorQueryRef().isBlank()) {
            try {
                vendorQuery = queries.get(a.getVendorQueryRef());
            } catch (Exception e) {
                log.warn("linked vendor query {} not resolvable for {} ({})",
                        a.getVendorQueryRef(), ref, e.getMessage());
            }
        }
        return new View(a, vendorQuery);
    }

    @Transactional(readOnly = true)
    public List<MonitoringArtifact> list(String subjectRef, String status, String type) {
        if (subjectRef != null && !subjectRef.isBlank()) {
            return repo.findBySubjectRefOrderByIdDesc(subjectRef);
        }
        if (status != null && !status.isBlank()) {
            return repo.findByStatusOrderByIdDesc(parseStatus(status));
        }
        if (type != null && !type.isBlank()) {
            return repo.findByArtifactTypeOrderByIdDesc(type.trim());
        }
        return repo.findAllByOrderByIdDesc();
    }

    // =============================================================== internals

    private MonitoringArtifact require(String ref) {
        return repo.findByArtifactRef(ref)
                .orElseThrow(() -> ApiException.notFound("Monitoring artifact " + ref + " not found"));
    }

    private void requireOwner(MonitoringArtifact a, String actor, String what) {
        if (!a.getOwner().equalsIgnoreCase(actor)) {
            throw ApiException.forbiddenAutonomy(
                    "Only the owner ('" + a.getOwner() + "') may " + what + " artifact " + a.getArtifactRef());
        }
    }

    /** Role gate on top of name-equality SoD; fail-open on a directory outage (SoD still applies). */
    private void requireRole(String actor, Set<String> allowed, String what) {
        ActorDirectory dir = directory.getIfAvailable();
        if (dir == null) return;                       // no directory bean — nothing to enforce
        Set<String> roles = dir.rolesFor(actor);
        if (roles == null) return;                     // directory outage — fail open (ActorDirectory logs WARN)
        if (roles.stream().noneMatch(allowed::contains)) {
            throw ApiException.forbiddenAutonomy("Actor '" + actor + "' may not " + what
                    + " (needs one of " + allowed + ", holds " + roles + ") — see the ACTOR_ROLE master");
        }
    }

    private static Map<String, Object> materialiseSections(List<Section> template) {
        Map<String, Object> sections = new LinkedHashMap<>();
        for (Section s : template) {
            Map<String, Object> cell = new LinkedHashMap<>();
            cell.put("label", s.label());
            cell.put("content", "");
            sections.put(s.key(), cell);
        }
        return sections;
    }

    private static MonitoringArtifactStatus parseStatus(String status) {
        try {
            return MonitoringArtifactStatus.valueOf(status.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("Unknown status '" + status + "'");
        }
    }

    private static String newRef() {
        final String alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
        StringBuilder sb = new StringBuilder("MON-");
        for (int i = 0; i < 6; i++) {
            sb.append(alphabet.charAt(ThreadLocalRandom.current().nextInt(alphabet.length())));
        }
        return sb.toString();
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }
}
