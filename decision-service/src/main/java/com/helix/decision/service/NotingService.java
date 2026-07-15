package com.helix.decision.service;

import com.helix.common.audit.AuditService;
import com.helix.common.rbac.ActorDirectory;
import com.helix.common.web.ApiException;
import com.helix.decision.client.UpstreamClient;
import com.helix.decision.client.UpstreamClient.MasterRecordDto;
import com.helix.decision.client.UpstreamClient.RulePackDto;
import com.helix.decision.dto.NotingDtos.CreateNotingRequest;
import com.helix.decision.entity.Noting;
import com.helix.decision.entity.Noting.Status;
import com.helix.decision.repo.NotingRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The Noting engine (Indian-bank governed decision RECORDS). A noting routes for
 * approval — DoA-based or fixed-role, driven entirely by the {@code NOTING_TYPE}
 * master (config-as-data) — captures a named human approval, optionally a CAD
 * authorisation, and supports reject / reverse / withdraw. It is authoritative as a
 * record but <b>never mutates an authoritative figure</b>: it makes no write call to
 * limits, exposure, rating or pricing. SoD (approver ≠ raiser) and role gates are
 * enforced server-side; a mirror to the case layer is best-effort and never blocks.
 */
@Service
public class NotingService {

    private static final Logger log = LoggerFactory.getLogger(NotingService.class);

    /** Roles permitted to authorise the CAD stage of a noting. */
    private static final Set<String> CAD_AUTHORITY_ROLES = Set.of("CAD_OPS", "CAD_HEAD", "CREDIT_ADMIN");

    private final NotingRepository notings;
    private final UpstreamClient upstream;
    private final DoaRouter router;
    private final ActorDirectory roles;
    private final AuditService audit;

    /**
     * Optional best-effort case-management mirror; bean absent when the workflow-service
     * URL isn't configured. The noting status machine is authoritative and completely
     * unaffected — a mirror failure is swallowed by the client and never reaches this txn.
     */
    private final com.helix.common.workflow.TaskClient taskClient;

    public NotingService(NotingRepository notings, UpstreamClient upstream, DoaRouter router,
                         ActorDirectory roles, AuditService audit,
                         @Autowired(required = false) com.helix.common.workflow.TaskClient taskClient) {
        this.notings = notings;
        this.upstream = upstream;
        this.router = router;
        this.roles = roles;
        this.audit = audit;
        this.taskClient = taskClient;
    }

    // ---- create / read ----

    @Transactional
    public Noting create(CreateNotingRequest req, String actor) {
        Noting n = new Noting();
        n.setNotingRef(generateRef());
        n.setNotingType(req.notingType().trim().toUpperCase());
        n.setSubjectType(req.subjectType() == null || req.subjectType().isBlank() ? "Application" : req.subjectType());
        n.setSubjectRef(req.subjectRef());
        n.setTitle(req.title());
        n.setNarrative(req.narrative());
        n.setRaisedBy(actor);
        n.setStatus(Status.DRAFT);
        n.setCadRequired(false);
        n.setPayload(req.payload() == null ? Map.of() : req.payload());
        Noting saved = notings.save(n);
        audit.human(actor, "NOTING_CREATED", "Noting", saved.getNotingRef(),
                "%s noting created for %s: %s".formatted(saved.getNotingType(), saved.getSubjectRef(), saved.getTitle()),
                Map.of("notingType", saved.getNotingType(), "subjectRef", saved.getSubjectRef()));
        return saved;
    }

    @Transactional(readOnly = true)
    public Noting get(String ref) {
        return notings.findByNotingRef(ref)
                .orElseThrow(() -> ApiException.notFound("No noting: " + ref));
    }

    @Transactional(readOnly = true)
    public List<Noting> list(String subjectRef, String status, String type) {
        List<Noting> base;
        if (subjectRef != null && !subjectRef.isBlank()) {
            base = notings.findBySubjectRefOrderByCreatedAtDesc(subjectRef);
        } else if (type != null && !type.isBlank()) {
            base = notings.findByNotingTypeOrderByCreatedAtDesc(type.trim().toUpperCase());
        } else {
            base = notings.findAllByOrderByCreatedAtDesc();
        }
        if (status == null || status.isBlank()) {
            return base;
        }
        Status want = parseStatus(status);
        return base.stream().filter(n -> n.getStatus() == want).toList();
    }

    // ---- workflow transitions ----

    /** DRAFT → PENDING_APPROVAL. Resolves routing from the NOTING_TYPE master (config-as-data). */
    @Transactional
    public Noting submit(String ref, String actor) {
        Noting n = get(ref);
        if (n.getStatus() != Status.DRAFT) {
            throw ApiException.conflict("Only a DRAFT noting can be submitted (is " + n.getStatus() + ")");
        }
        NotingTypeConfig cfg = resolveType(n.getNotingType());
        n.setCadRequired(cfg.cadRequired());

        String routing;
        String approverRole;
        String ruleApplied;
        if ("DOA".equalsIgnoreCase(cfg.routing())) {
            routing = "DOA";
            String jurisdiction = str(n.getPayload(), "jurisdiction", "IN-RBI");
            String grade = str(n.getPayload(), "grade", "BBB");
            double amount = dbl(n.getPayload(), "amount", 0d);
            boolean hasDeviation = bool(n.getPayload(), "hasDeviation", false);
            RulePackDto doaPack = upstream.doaMatrix(jurisdiction);
            DoaRouter.Routing r = router.route(amount, grade, hasDeviation, doaPack);
            approverRole = r.requiredAuthority();
            ruleApplied = r.ruleApplied();
        } else {
            routing = "FIXED_ROLE";
            approverRole = cfg.approverRole();
            ruleApplied = "fixed role " + approverRole + " from NOTING_TYPE master";
        }
        n.setRouting(routing);
        n.setApproverRole(approverRole);
        n.setStatus(Status.PENDING_APPROVAL);
        Noting saved = notings.save(n);

        // Best-effort case-management mirror: an approval task on the approver's queue.
        // Advisory task tracking only — the Noting above is authoritative and unchanged by this call.
        if (taskClient != null) {
            taskClient.createTask("Noting", saved.getNotingRef(), "NOTING_APPROVAL",
                    "NOTING_APPROVALS", null, "NTG:" + saved.getNotingRef(), null, actor,
                    Map.of("notingRef", saved.getNotingRef(), "notingType", saved.getNotingType(),
                            "routing", routing, "approverRole", approverRole,
                            "cadRequired", saved.isCadRequired()));
        }

        audit.human(actor, "NOTING_SUBMITTED", "Noting", saved.getNotingRef(),
                "Routed to %s (%s%s)".formatted(approverRole, routing,
                        saved.isCadRequired() ? ", CAD required" : ""),
                Map.of("routing", routing, "approverRole", approverRole,
                        "ruleApplied", ruleApplied, "cadRequired", saved.isCadRequired()));
        return saved;
    }

    /** PENDING_APPROVAL → APPROVED, or → PENDING_CAD when the type requires CAD authorisation. */
    @Transactional
    public Noting approve(String ref, String note, String actor) {
        Noting n = get(ref);
        if (n.getStatus() != Status.PENDING_APPROVAL) {
            throw ApiException.conflict("Only a PENDING_APPROVAL noting can be approved (is " + n.getStatus() + ")");
        }
        // SoD — the approver must differ from the raiser.
        if (actor != null && actor.equalsIgnoreCase(n.getRaisedBy())) {
            throw ApiException.forbiddenAutonomy(
                    "Approver cannot be the raiser ('" + n.getRaisedBy() + "') — segregation of duties");
        }
        // The actor must hold the routed authority/role.
        requireRoutedAuthority(n, actor);

        n.setApprover(actor);
        n.setDecidedBy(actor);
        n.setDecidedAt(Instant.now());
        n.setDecisionNote(note);
        boolean toCad = n.isCadRequired();
        n.setStatus(toCad ? Status.PENDING_CAD : Status.APPROVED);
        Noting saved = notings.save(n);

        if (taskClient != null && !toCad) {
            taskClient.complete("NTG:" + saved.getNotingRef(), "approved", actor);
        }
        audit.human(actor, "NOTING_APPROVED", "Noting", saved.getNotingRef(),
                "%s approved by %s%s".formatted(saved.getNotingType(), actor,
                        toCad ? " — pending CAD authorisation" : ""),
                Map.of("approverRole", saved.getApproverRole(), "status", saved.getStatus().name(),
                        "cadRequired", toCad));
        return saved;
    }

    /** PENDING_CAD → AUTHORIZED. Actor must hold a CAD authority role. */
    @Transactional
    public Noting cadAuthorize(String ref, String note, String actor) {
        Noting n = get(ref);
        if (n.getStatus() != Status.PENDING_CAD) {
            throw ApiException.conflict("Only a PENDING_CAD noting can be authorised (is " + n.getStatus() + ")");
        }
        requireCadAuthority(actor);
        n.setAuthorisedBy(actor);
        n.setAuthorisedAt(Instant.now());
        if (note != null && !note.isBlank()) {
            n.setDecisionNote(note);
        }
        n.setStatus(Status.AUTHORIZED);
        Noting saved = notings.save(n);
        audit.human(actor, "NOTING_AUTHORIZED", "Noting", saved.getNotingRef(),
                "%s CAD-authorised by %s".formatted(saved.getNotingType(), actor),
                Map.of("authorisedBy", actor, "status", saved.getStatus().name()));
        return saved;
    }

    /** PENDING_APPROVAL | PENDING_CAD → REJECTED (reason mandatory). */
    @Transactional
    public Noting reject(String ref, String reason, String actor) {
        Noting n = get(ref);
        if (n.getStatus() != Status.PENDING_APPROVAL && n.getStatus() != Status.PENDING_CAD) {
            throw ApiException.conflict("Only a pending noting can be rejected (is " + n.getStatus() + ")");
        }
        if (reason == null || reason.isBlank()) {
            throw ApiException.badRequest("A rejection reason is mandatory");
        }
        n.setStatus(Status.REJECTED);
        n.setDecidedBy(actor);
        n.setDecidedAt(Instant.now());
        n.setDecisionNote(reason);
        Noting saved = notings.save(n);
        audit.human(actor, "NOTING_REJECTED", "Noting", saved.getNotingRef(),
                "%s rejected by %s: %s".formatted(saved.getNotingType(), actor, reason),
                Map.of("reason", reason));
        return saved;
    }

    /** APPROVED | AUTHORIZED → REVERSED (reason mandatory; reverser role-gated). */
    @Transactional
    public Noting reverse(String ref, String reason, String actor) {
        Noting n = get(ref);
        if (n.getStatus() != Status.APPROVED && n.getStatus() != Status.AUTHORIZED) {
            throw ApiException.conflict("Only an APPROVED or AUTHORIZED noting can be reversed (is " + n.getStatus() + ")");
        }
        if (reason == null || reason.isBlank()) {
            throw ApiException.badRequest("A reversal reason is mandatory");
        }
        // Reverser must hold the routed authority (an AUTHORIZED noting may also be reversed by CAD authority).
        if (n.getStatus() == Status.AUTHORIZED && holdsCadAuthority(actor)) {
            // ok — CAD authority may unwind its own authorisation
        } else {
            requireRoutedAuthority(n, actor);
        }
        n.setStatus(Status.REVERSED);
        n.setDecisionNote(reason);
        Noting saved = notings.save(n);
        audit.human(actor, "NOTING_REVERSED", "Noting", saved.getNotingRef(),
                "%s reversed by %s: %s".formatted(saved.getNotingType(), actor, reason),
                Map.of("reason", reason, "reversedBy", actor));
        return saved;
    }

    /** DRAFT | PENDING_APPROVAL → WITHDRAWN. Raiser only. */
    @Transactional
    public Noting withdraw(String ref, String actor) {
        Noting n = get(ref);
        if (n.getStatus() != Status.DRAFT && n.getStatus() != Status.PENDING_APPROVAL) {
            throw ApiException.conflict("Only a pre-approval noting can be withdrawn (is " + n.getStatus() + ")");
        }
        if (actor == null || !actor.equalsIgnoreCase(n.getRaisedBy())) {
            throw ApiException.forbiddenAutonomy(
                    "Only the raiser ('" + n.getRaisedBy() + "') can withdraw this noting");
        }
        n.setStatus(Status.WITHDRAWN);
        Noting saved = notings.save(n);
        audit.human(actor, "NOTING_WITHDRAWN", "Noting", saved.getNotingRef(),
                "%s withdrawn by raiser %s".formatted(saved.getNotingType(), actor), Map.of());
        return saved;
    }

    // ---- routing / role resolution ----

    /**
     * Resolves the routed authority requirement. For DOA the actor's highest role rank must
     * meet the routed authority; for FIXED_ROLE the actor must hold that exact role. A
     * directory outage fails open (matching {@link com.helix.common.rbac.ActorDirectory}
     * and {@code DecisionService}); an actor absent from a HEALTHY directory is denied.
     */
    private void requireRoutedAuthority(Noting n, String actor) {
        if (actor == null || actor.isBlank()) {
            throw ApiException.forbiddenAutonomy("A named human actor is required to act on a noting");
        }
        Set<String> actorRoles = roles.rolesFor(actor);
        if (actorRoles == null) {
            return;   // directory outage — fail open (ActorDirectory logs WARN)
        }
        String required = n.getApproverRole();
        boolean ok;
        if ("DOA".equalsIgnoreCase(n.getRouting())) {
            int need = Authorities.rank(required);
            ok = actorRoles.stream().anyMatch(r -> Authorities.rank(r) >= need);
        } else {
            ok = required != null && actorRoles.contains(required);
        }
        if (!ok) {
            throw ApiException.forbiddenAutonomy(
                    "This noting requires " + required + " authority — actor '" + actor
                    + "' holds " + actorRoles + " (insufficient); the request-body role is not trusted");
        }
    }

    private void requireCadAuthority(String actor) {
        if (actor == null || actor.isBlank()) {
            throw ApiException.forbiddenAutonomy("A named human actor is required to authorise a noting");
        }
        Set<String> actorRoles = roles.rolesFor(actor);
        if (actorRoles == null) {
            return;   // directory outage — fail open
        }
        if (actorRoles.stream().noneMatch(CAD_AUTHORITY_ROLES::contains)) {
            throw ApiException.forbiddenAutonomy(
                    "CAD authorisation requires one of " + CAD_AUTHORITY_ROLES + " — actor '" + actor
                    + "' holds " + actorRoles);
        }
    }

    private boolean holdsCadAuthority(String actor) {
        Set<String> actorRoles = roles.rolesFor(actor);
        if (actorRoles == null) return false;
        return actorRoles.stream().anyMatch(CAD_AUTHORITY_ROLES::contains);
    }

    /** Immutable resolved config for a NOTING_TYPE. */
    private record NotingTypeConfig(String routing, String approverRole, boolean cadRequired) {
    }

    /**
     * Reads the NOTING_TYPE master row (config-as-data) for this type. Conservative built-in
     * fallback (FIXED_ROLE / CREDIT_HEAD / cadRequired=false) if config-service is unreachable
     * or the row is absent, so submit is never blocked by a config outage.
     */
    private NotingTypeConfig resolveType(String notingType) {
        try {
            List<MasterRecordDto> rows = upstream.masters("NOTING_TYPE");
            for (MasterRecordDto row : rows) {
                if (notingType.equalsIgnoreCase(row.recordKey())) {
                    Map<String, Object> p = row.payload() == null ? Map.of() : row.payload();
                    String routing = str(p, "routing", "FIXED_ROLE");
                    String approverRole = str(p, "approverRole", "CREDIT_HEAD");
                    boolean cadRequired = bool(p, "cadRequired", false);
                    return new NotingTypeConfig(routing, approverRole, cadRequired);
                }
            }
            log.warn("NOTING_TYPE master row '{}' not found; using conservative fallback (FIXED_ROLE/CREDIT_HEAD)",
                    notingType);
        } catch (Exception e) {
            log.warn("NOTING_TYPE master unavailable for '{}' ({}); using conservative fallback", notingType, e.getMessage());
        }
        return new NotingTypeConfig("FIXED_ROLE", "CREDIT_HEAD", false);
    }

    // ---- helpers ----

    private String generateRef() {
        for (int i = 0; i < 8; i++) {
            String ref = "NTG-" + UUID.randomUUID().toString().replace("-", "")
                    .substring(0, 6).toUpperCase();
            if (!notings.existsByNotingRef(ref)) {
                return ref;
            }
        }
        return "NTG-" + UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase();
    }

    private Status parseStatus(String value) {
        try {
            return Status.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("Unknown noting status: " + value);
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
        if (v instanceof Number n) return n.doubleValue();
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
