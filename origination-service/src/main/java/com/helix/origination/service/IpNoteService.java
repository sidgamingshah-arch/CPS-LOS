package com.helix.origination.service;

import com.helix.common.audit.AuditService;
import com.helix.common.rbac.ActorDirectory;
import com.helix.common.web.ApiException;
import com.helix.origination.dto.Dtos.CreateApplicationRequest;
import com.helix.origination.dto.IpNoteDtos.CreateIpNoteRequest;
import com.helix.origination.entity.IpNote;
import com.helix.origination.entity.IpNote.Status;
import com.helix.origination.entity.LoanApplication;
import com.helix.origination.repo.IpNoteRepository;
import com.helix.origination.repo.LoanApplicationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The In-Principle (IP) note engine. An RM raises a lightweight sponsorship note for a
 * prospect / obligor <i>before</i> the full application; it routes for a named-human
 * credit sign-off, and once APPROVED is <b>converted</b> into a real {@link LoanApplication}
 * via the existing origination application-creation path ({@link OriginationService#create}).
 * The created application carries the originating {@code ipNoteRef}; the note is stamped
 * with the created {@code applicationRef}.
 *
 * <p>The note is authoritative as a RECORD — it makes no write to a limit, exposure,
 * rating or price. SoD (approver ≠ raiser) and an authority-tier gate (the approver must
 * actually hold a credit-approval role from the {@code ACTOR_ROLE} master) are enforced
 * server-side; a directory outage fails open with a WARN. A best-effort mirror to the
 * case layer is null-guarded and never throws into this transaction.</p>
 */
@Service
public class IpNoteService {

    private static final Logger log = LoggerFactory.getLogger(IpNoteService.class);

    /** Roles authorised to approve an IP note (resolved from the ACTOR_ROLE master). */
    private static final Set<String> APPROVAL_AUTHORITY = Set.of(
            "CREDIT_OFFICER", "CREDIT_HEAD", "CREDIT_COMMITTEE", "CRO", "BOARD_COMMITTEE");

    private final IpNoteRepository ipNotes;
    private final OriginationService origination;
    private final LoanApplicationRepository applications;
    private final ActorDirectory roles;
    private final AuditService audit;

    /**
     * Optional best-effort case-management mirror; bean absent when the workflow-service
     * URL isn't configured. The IP-note status machine is authoritative and completely
     * unaffected — a mirror failure is swallowed by the client and never reaches this txn.
     */
    private final com.helix.common.workflow.TaskClient taskClient;

    public IpNoteService(IpNoteRepository ipNotes, OriginationService origination,
                         LoanApplicationRepository applications, ActorDirectory roles,
                         AuditService audit,
                         @Autowired(required = false) com.helix.common.workflow.TaskClient taskClient) {
        this.ipNotes = ipNotes;
        this.origination = origination;
        this.applications = applications;
        this.roles = roles;
        this.audit = audit;
        this.taskClient = taskClient;
    }

    // ---- create / read ----

    @Transactional
    public IpNote create(CreateIpNoteRequest req, String actor) {
        IpNote n = new IpNote();
        n.setIpNoteRef(generateRef());
        n.setCounterpartyId(req.counterpartyId());
        n.setCounterpartyRef(req.counterpartyRef());
        n.setCounterpartyName(req.counterpartyName());
        n.setJurisdiction(req.jurisdiction());
        n.setSegment(req.segment());
        n.setFacilityType(req.facilityType());
        n.setProposedAmount(req.proposedAmount());
        n.setCurrency(req.currency());
        n.setTenorMonths(req.tenorMonths());
        n.setPurpose(req.purpose());
        n.setProspectSummary(req.prospectSummary());
        n.setPayload(req.payload() == null ? Map.of() : req.payload());
        n.setRaisedBy(actor);
        n.setStatus(Status.DRAFT);
        IpNote saved = ipNotes.save(n);
        audit.human(actor, "IP_NOTE_CREATED", "IpNote", saved.getIpNoteRef(),
                "In-principle note for %s: %.0f %s %s".formatted(
                        saved.getCounterpartyName(), saved.getProposedAmount(),
                        saved.getCurrency(), saved.getFacilityType()),
                Map.of("counterpartyRef", saved.getCounterpartyRef(),
                        "facilityType", saved.getFacilityType(),
                        "proposedAmount", saved.getProposedAmount()));
        return saved;
    }

    @Transactional(readOnly = true)
    public IpNote get(String ref) {
        return ipNotes.findByIpNoteRef(ref)
                .orElseThrow(() -> ApiException.notFound("No IP note: " + ref));
    }

    @Transactional(readOnly = true)
    public List<IpNote> list(String counterpartyRef, String status) {
        List<IpNote> base;
        if (counterpartyRef != null && !counterpartyRef.isBlank()) {
            base = ipNotes.findByCounterpartyRefOrderByCreatedAtDesc(counterpartyRef);
        } else {
            base = ipNotes.findAllByOrderByCreatedAtDesc();
        }
        if (status == null || status.isBlank()) {
            return base;
        }
        Status want = parseStatus(status);
        return base.stream().filter(n -> n.getStatus() == want).toList();
    }

    // ---- workflow transitions ----

    /** DRAFT → PENDING_APPROVAL. Best-effort case-mirror to the approver's queue. */
    @Transactional
    public IpNote submit(String ref, String actor) {
        IpNote n = get(ref);
        if (n.getStatus() != Status.DRAFT) {
            throw ApiException.conflict("Only a DRAFT IP note can be submitted (is " + n.getStatus() + ")");
        }
        n.setStatus(Status.PENDING_APPROVAL);
        IpNote saved = ipNotes.save(n);

        // Best-effort case-management mirror: an approval task on the approver's queue.
        // Advisory task tracking only — the IP note above is authoritative and unchanged by this call.
        if (taskClient != null) {
            taskClient.createTask("IpNote", saved.getIpNoteRef(), "IP_NOTE_APPROVAL",
                    "IP_NOTE_APPROVALS", null, "IPN:" + saved.getIpNoteRef(), null, actor,
                    Map.of("ipNoteRef", saved.getIpNoteRef(),
                            "counterpartyRef", saved.getCounterpartyRef(),
                            "proposedAmount", saved.getProposedAmount()));
        }

        audit.human(actor, "IP_NOTE_SUBMITTED", "IpNote", saved.getIpNoteRef(),
                "Submitted for credit sign-off", Map.of("status", saved.getStatus().name()));
        return saved;
    }

    /** PENDING_APPROVAL → APPROVED. SoD (approver ≠ raiser) + authority-tier gate. */
    @Transactional
    public IpNote approve(String ref, String note, String actor) {
        IpNote n = get(ref);
        if (n.getStatus() != Status.PENDING_APPROVAL) {
            throw ApiException.conflict("Only a PENDING_APPROVAL IP note can be approved (is " + n.getStatus() + ")");
        }
        // SoD — the approver must differ from the raiser.
        if (actor != null && actor.equalsIgnoreCase(n.getRaisedBy())) {
            throw ApiException.forbiddenAutonomy(
                    "Approver cannot be the raiser ('" + n.getRaisedBy() + "') — segregation of duties");
        }
        // The actor must hold a credit-approval authority tier.
        String held = requireApprovalAuthority(actor);
        n.setApproverRole(held);
        n.setStatus(Status.APPROVED);
        n.setDecidedBy(actor);
        n.setDecidedAt(Instant.now());
        n.setDecisionNote(note);
        IpNote saved = ipNotes.save(n);

        if (taskClient != null) {
            taskClient.complete("IPN:" + saved.getIpNoteRef(), "approved", actor);
        }
        audit.human(actor, "IP_NOTE_APPROVED", "IpNote", saved.getIpNoteRef(),
                "Approved by %s (%s)".formatted(actor, held == null ? "authority" : held),
                Map.of("approverRole", held == null ? "" : held, "status", saved.getStatus().name()));
        return saved;
    }

    /** PENDING_APPROVAL → REJECTED (reason mandatory). SoD (approver ≠ raiser) + authority-tier gate. */
    @Transactional
    public IpNote reject(String ref, String reason, String actor) {
        IpNote n = get(ref);
        if (n.getStatus() != Status.PENDING_APPROVAL) {
            throw ApiException.conflict("Only a PENDING_APPROVAL IP note can be rejected (is " + n.getStatus() + ")");
        }
        if (reason == null || reason.isBlank()) {
            throw ApiException.badRequest("A rejection reason is mandatory");
        }
        if (actor != null && actor.equalsIgnoreCase(n.getRaisedBy())) {
            throw ApiException.forbiddenAutonomy(
                    "Decider cannot be the raiser ('" + n.getRaisedBy() + "') — segregation of duties");
        }
        requireApprovalAuthority(actor);
        n.setStatus(Status.REJECTED);
        n.setDecidedBy(actor);
        n.setDecidedAt(Instant.now());
        n.setDecisionNote(reason);
        IpNote saved = ipNotes.save(n);
        if (taskClient != null) {
            taskClient.complete("IPN:" + saved.getIpNoteRef(), "rejected", actor);
        }
        audit.human(actor, "IP_NOTE_REJECTED", "IpNote", saved.getIpNoteRef(),
                "Rejected by %s: %s".formatted(actor, reason), Map.of("reason", reason));
        return saved;
    }

    /** DRAFT | PENDING_APPROVAL → WITHDRAWN. Raiser only. */
    @Transactional
    public IpNote withdraw(String ref, String actor) {
        IpNote n = get(ref);
        if (n.getStatus() != Status.DRAFT && n.getStatus() != Status.PENDING_APPROVAL) {
            throw ApiException.conflict("Only a pre-approval IP note can be withdrawn (is " + n.getStatus() + ")");
        }
        if (actor == null || !actor.equalsIgnoreCase(n.getRaisedBy())) {
            throw ApiException.forbiddenAutonomy(
                    "Only the raiser ('" + n.getRaisedBy() + "') can withdraw this IP note");
        }
        n.setStatus(Status.WITHDRAWN);
        IpNote saved = ipNotes.save(n);
        if (taskClient != null) {
            taskClient.complete("IPN:" + saved.getIpNoteRef(), "withdrawn", actor);
        }
        audit.human(actor, "IP_NOTE_WITHDRAWN", "IpNote", saved.getIpNoteRef(),
                "Withdrawn by raiser %s".formatted(actor), Map.of());
        return saved;
    }

    /**
     * APPROVED → CONVERTED. Materialises a real {@link LoanApplication} via the existing
     * origination application-creation path, stamps the originating {@code ipNoteRef} on
     * the created application, and records the created {@code applicationRef} on the note.
     */
    @Transactional
    public IpNote convert(String ref, String actor) {
        IpNote n = get(ref);
        if (n.getStatus() != Status.APPROVED) {
            throw ApiException.conflict("Only an APPROVED IP note can be converted (is " + n.getStatus() + ")");
        }
        if (n.getApplicationRef() != null && !n.getApplicationRef().isBlank()) {
            throw ApiException.conflict("IP note " + ref + " already converted to " + n.getApplicationRef());
        }
        Map<String, Object> p = n.getPayload() == null ? Map.of() : n.getPayload();
        CreateApplicationRequest car = new CreateApplicationRequest(
                n.getCounterpartyId(), n.getCounterpartyRef(), n.getCounterpartyName(),
                n.getJurisdiction(), n.getSegment(), n.getFacilityType(),
                n.getProposedAmount(), n.getCurrency(), n.getTenorMonths(), n.getPurpose(),
                str(p, "collateralType", null), dbl(p, "collateralValue", 0d), bool(p, "secured", false));
        // Reuse the existing application-creation path (audits APPLICATION_CREATED, mirrors workflow).
        LoanApplication app = origination.create(car, actor);
        app.setIpNoteRef(n.getIpNoteRef());
        applications.save(app);

        n.setApplicationRef(app.getReference());
        n.setStatus(Status.CONVERTED);
        IpNote saved = ipNotes.save(n);
        audit.human(actor, "IP_NOTE_CONVERTED", "IpNote", saved.getIpNoteRef(),
                "Converted to application %s".formatted(app.getReference()),
                Map.of("applicationRef", app.getReference(),
                        "counterpartyRef", saved.getCounterpartyRef()));
        return saved;
    }

    // ---- authority resolution ----

    /**
     * The approver must actually HOLD a credit-approval authority tier (resolved from the
     * ACTOR_ROLE master). A directory outage fails open with a WARN (ActorDirectory
     * parity). Returns the matched role for the audit record, or null on a fail-open outage.
     */
    private String requireApprovalAuthority(String actor) {
        if (actor == null || actor.isBlank()) {
            throw ApiException.forbiddenAutonomy("A named human actor is required to decide an IP note");
        }
        Set<String> actorRoles = roles.rolesFor(actor);
        if (actorRoles == null) {
            log.warn("ACTOR_ROLE directory unavailable — allowing '{}' to decide IP note (fail-open)", actor);
            return null;   // directory outage — fail open (ActorDirectory logs WARN)
        }
        String matched = actorRoles.stream()
                .map(r -> r == null ? "" : r.toUpperCase())
                .filter(APPROVAL_AUTHORITY::contains)
                .findFirst().orElse(null);
        if (matched == null) {
            throw ApiException.forbiddenAutonomy(
                    "IP-note approval requires one of " + APPROVAL_AUTHORITY + " — actor '" + actor
                    + "' holds " + actorRoles + " (insufficient); see the ACTOR_ROLE master");
        }
        return matched;
    }

    // ---- helpers ----

    private String generateRef() {
        for (int i = 0; i < 8; i++) {
            String ref = "IPN-" + UUID.randomUUID().toString().replace("-", "")
                    .substring(0, 6).toUpperCase();
            if (!ipNotes.existsByIpNoteRef(ref)) {
                return ref;
            }
        }
        return "IPN-" + UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase();
    }

    private Status parseStatus(String value) {
        try {
            return Status.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("Unknown IP note status: " + value);
        }
    }

    private static String str(Map<String, Object> m, String key, String dflt) {
        if (m == null) return dflt;
        Object v = m.get(key);
        return v == null || String.valueOf(v).isBlank() ? dflt : String.valueOf(v);
    }

    private static double dbl(Map<String, Object> m, String key, double dflt) {
        if (m == null) return dflt;
        Object v = m.get(key);
        if (v instanceof Number num) return num.doubleValue();
        try {
            return v == null ? dflt : Double.parseDouble(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            return dflt;
        }
    }

    private static boolean bool(Map<String, Object> m, String key, boolean dflt) {
        if (m == null) return dflt;
        Object v = m.get(key);
        if (v instanceof Boolean b) return b;
        if (v == null) return dflt;
        return "true".equalsIgnoreCase(String.valueOf(v).trim());
    }
}
