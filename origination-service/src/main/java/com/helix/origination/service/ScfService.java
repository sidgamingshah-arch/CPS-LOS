package com.helix.origination.service;

import com.helix.common.audit.AuditService;
import com.helix.common.web.ApiException;
import com.helix.origination.client.ScfEligibilityClient;
import com.helix.origination.client.ScfLimitClient;
import com.helix.origination.client.ScfNotingClient;
import com.helix.origination.dto.ScfDtos.AddSpokeRequest;
import com.helix.origination.dto.ScfDtos.CreateProgramRequest;
import com.helix.origination.dto.ScfDtos.ProgramView;
import com.helix.origination.entity.ScfProgram;
import com.helix.origination.entity.ScfSpoke;
import com.helix.origination.repo.ScfProgramRepository;
import com.helix.origination.repo.ScfSpokeRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Supply-Chain Finance (SCF) product-paper engine. An anchor-backed programme is drafted,
 * spokes (suppliers / distributors) are added and judged deterministically against the
 * pinned {@code SCF_ELIGIBILITY} snapshot + per-spoke cap, the programme is submitted for
 * approval (raising a best-effort linked {@code PRODUCT_PAPER} noting in decision-service),
 * and approved under segregation of duties (approver ≠ raiser) + credit authority.
 *
 * <p>Governance: the eligibility engine is deterministic (no GenAI in the figure path); the
 * programme limit is registered into the limit tree via limit-service's OWN governed API
 * (SCF never writes an authoritative limit itself); and the linked noting is a record, never
 * a figure mutation. Cross-service calls (noting, limit) are best-effort — an outage on
 * either never fails an SCF transition.
 */
@Service
public class ScfService {

    private static final Logger log = LoggerFactory.getLogger(ScfService.class);

    private static final Set<String> PROGRAM_TYPES = Set.of("VENDOR", "DEALER");

    /** Actors carrying credit authority to approve/reject an SCF product paper. */
    private static final Set<String> APPROVAL_AUTHORITIES = Set.of(
            "credit.officer", "credit.head", "credit.committee", "sanctioning.authority",
            "chief.credit.officer", "cro");

    private final ScfProgramRepository programs;
    private final ScfSpokeRepository spokes;
    private final ScfEligibilityClient eligibility;
    private final ScfNotingClient notings;
    private final ScfLimitClient limits;
    private final AuditService audit;

    public ScfService(ScfProgramRepository programs, ScfSpokeRepository spokes,
                      ScfEligibilityClient eligibility, ScfNotingClient notings,
                      ScfLimitClient limits, AuditService audit) {
        this.programs = programs;
        this.spokes = spokes;
        this.eligibility = eligibility;
        this.notings = notings;
        this.limits = limits;
        this.audit = audit;
    }

    // ------------------------------------------------------------------ create

    @Transactional
    public ProgramView createProgram(CreateProgramRequest req, String actor) {
        if (req.anchorRef() == null || req.anchorRef().isBlank()) {
            throw ApiException.badRequest("anchorRef is required");
        }
        String type = req.programType() == null ? "" : req.programType().toUpperCase();
        if (!PROGRAM_TYPES.contains(type)) {
            throw ApiException.badRequest("Unknown programType: " + type + " (expected " + PROGRAM_TYPES + ")");
        }
        double limit = nz(req.programLimit());
        double cap = nz(req.perSpokeCap());
        if (limit <= 0) throw ApiException.badRequest("programLimit must be positive");
        if (cap < 0) throw ApiException.badRequest("perSpokeCap cannot be negative");
        if (cap > limit) throw ApiException.badRequest("perSpokeCap (" + cap + ") cannot exceed programLimit (" + limit + ")");

        ScfProgram p = new ScfProgram();
        p.setScfRef(generateRef());
        p.setAnchorRef(req.anchorRef());
        p.setAnchorName(req.anchorName());
        p.setProgramType(type);
        p.setProgramLimit(limit);
        p.setPerSpokeCap(cap);
        p.setCurrency(req.currency() == null || req.currency().isBlank() ? "INR" : req.currency().toUpperCase());
        // Pin the eligibility criteria at create time (config-as-data snapshot; conservative fallback).
        p.setEligibilitySnapshot(eligibility.resolve(type));
        p.setStatus("DRAFT");
        p.setRaisedBy(actor);
        ScfProgram saved = programs.save(p);
        audit.human(actor, "SCF_PROGRAM_CREATED", "ScfProgram", saved.getScfRef(),
                "%s SCF programme for anchor %s — limit %.0f %s, per-spoke cap %.0f".formatted(
                        type, saved.getAnchorRef(), limit, saved.getCurrency(), cap),
                Map.of("programType", type, "anchorRef", saved.getAnchorRef(), "programLimit", limit));
        return view(saved.getScfRef());
    }

    // ------------------------------------------------------------------ spokes

    @Transactional
    public ProgramView addSpoke(String scfRef, AddSpokeRequest req, String actor) {
        ScfProgram p = program(scfRef);
        if (!"DRAFT".equals(p.getStatus())) {
            throw ApiException.conflict("Spokes can only be added while the programme is DRAFT (is " + p.getStatus() + ")");
        }
        if (req.spokeRef() == null || req.spokeRef().isBlank()) {
            throw ApiException.badRequest("spokeRef is required");
        }
        double requested = nz(req.requestedAmount());
        if (requested <= 0) throw ApiException.badRequest("requestedAmount must be positive");

        Eligibility e = evaluate(p, requested);
        ScfSpoke s = new ScfSpoke();
        s.setProgramRef(p.getScfRef());
        s.setSpokeRef(req.spokeRef());
        s.setSpokeName(req.spokeName());
        s.setRequestedAmount(requested);
        s.setEligibilityResult(e.result);
        s.setReasons(e.reasons);
        s.setApprovedCap(e.approvedCap);
        spokes.save(s);
        audit.human(actor, "SCF_SPOKE_ADDED", "ScfProgram", p.getScfRef(),
                "Spoke %s requested %.0f — eligibility %s".formatted(req.spokeRef(), requested, e.result),
                Map.of("spokeRef", req.spokeRef(), "eligibility", e.result, "approvedCap", e.approvedCap));
        return view(p.getScfRef());
    }

    /**
     * Deterministic per-spoke eligibility against the programme's pinned snapshot + caps.
     * No GenAI: the same inputs always yield the same PASS/FAIL + reasons + approved cap.
     */
    private Eligibility evaluate(ScfProgram p, double requested) {
        Map<String, Object> crit = p.getEligibilitySnapshot() == null || p.getEligibilitySnapshot().isEmpty()
                ? ScfEligibilityClient.builtInDefaults() : p.getEligibilitySnapshot();
        double min = num(crit, "minSpokeAmount", 0.0);
        double max = num(crit, "maxSpokeAmount", Double.MAX_VALUE);
        double maxPct = num(crit, "maxSpokePctOfProgram", 100.0);
        List<String> allowed = strList(crit, "allowedProgramTypes");

        List<String> reasons = new ArrayList<>();
        if (!allowed.isEmpty() && !allowed.contains(p.getProgramType())) {
            reasons.add("Programme type %s is not eligible per SCF_ELIGIBILITY (allowed: %s)"
                    .formatted(p.getProgramType(), allowed));
        }
        if (requested < min) {
            reasons.add("Requested %.0f is below the minimum spoke amount %.0f".formatted(requested, min));
        }
        if (requested > max) {
            reasons.add("Requested %.0f exceeds the maximum spoke amount %.0f".formatted(requested, max));
        }
        if (p.getPerSpokeCap() > 0 && requested > p.getPerSpokeCap()) {
            reasons.add("Requested %.0f exceeds the programme per-spoke cap %.0f".formatted(requested, p.getPerSpokeCap()));
        }
        if (p.getProgramLimit() > 0) {
            double pct = requested / p.getProgramLimit() * 100.0;
            if (pct > maxPct + 1e-9) {
                reasons.add("Requested is %.1f%% of the programme limit, above the %.1f%% ceiling".formatted(pct, maxPct));
            }
        }
        boolean pass = reasons.isEmpty();
        double approvedCap = pass ? requested : 0.0;
        return new Eligibility(pass ? "PASS" : "FAIL", reasons, approvedCap);
    }

    private record Eligibility(String result, List<String> reasons, double approvedCap) {
    }

    // --------------------------------------------------------------- workflow

    /** DRAFT → PENDING_APPROVAL; raise a best-effort linked PRODUCT_PAPER noting. */
    @Transactional
    public ProgramView submit(String scfRef, String actor) {
        ScfProgram p = program(scfRef);
        if (!"DRAFT".equals(p.getStatus())) {
            throw ApiException.conflict("Only a DRAFT programme can be submitted (is " + p.getStatus() + ")");
        }
        List<ScfSpoke> ss = spokes.findByProgramRefOrderByIdAsc(scfRef);
        if (ss.isEmpty()) {
            throw ApiException.badRequest("Add at least one spoke before submitting the programme");
        }
        p.setStatus("PENDING_APPROVAL");

        // Best-effort linked noting — a decision-service outage must NOT fail SCF submit.
        String title = "%s SCF product paper — anchor %s".formatted(p.getProgramType(), p.getAnchorRef());
        String narrative = "Supply-chain finance programme %s (%s), limit %.0f %s across %d spoke(s)."
                .formatted(p.getScfRef(), p.getProgramType(), p.getProgramLimit(), p.getCurrency(), ss.size());
        Map<String, Object> payload = Map.of(
                "scfRef", p.getScfRef(), "anchorRef", p.getAnchorRef(),
                "programType", p.getProgramType(), "programLimit", p.getProgramLimit(),
                "spokeCount", ss.size());
        String notingRef = notings.createProductPaperNoting(p.getScfRef(), title, narrative, payload, actor);
        if (notingRef != null) {
            p.setNotingRef(notingRef);
            log.info("SCF {} linked to PRODUCT_PAPER noting {}", p.getScfRef(), notingRef);
        } else {
            log.warn("SCF {} submitted without a linked noting (decision-service unavailable)", p.getScfRef());
        }
        programs.save(p);
        audit.human(actor, "SCF_PROGRAM_SUBMITTED", "ScfProgram", p.getScfRef(),
                "Submitted for approval%s".formatted(notingRef == null ? "" : " (noting " + notingRef + ")"),
                Map.of("status", "PENDING_APPROVAL", "notingRef", notingRef == null ? "" : notingRef));
        return view(p.getScfRef());
    }

    /** PENDING_APPROVAL → APPROVED; SoD (approver ≠ raiser) + credit authority; best-effort limit registration. */
    @Transactional
    public ProgramView approve(String scfRef, String note, String actor) {
        ScfProgram p = program(scfRef);
        if (!"PENDING_APPROVAL".equals(p.getStatus())) {
            throw ApiException.conflict("Only a PENDING_APPROVAL programme can be approved (is " + p.getStatus() + ")");
        }
        guardApprovalAuthority(p, actor);
        p.setStatus("APPROVED");
        p.setDecidedBy(actor);
        p.setDecidedAt(Instant.now());
        p.setDecisionNote(note);

        // Best-effort registration into limit-service's OWN governed limit tree — SCF never
        // writes an authoritative limit itself. A limit-service outage must NOT fail approval.
        String limitRef = limits.registerProgramLimit(p.getAnchorRef(), p.getScfRef(),
                p.getProgramLimit(), p.getCurrency(), actor);
        if (limitRef != null) {
            p.setRegisteredLimitRef(limitRef);
            log.info("SCF {} programme limit registered as limit node {}", p.getScfRef(), limitRef);
        } else {
            log.warn("SCF {} approved without a limit node (limit-service unavailable)", p.getScfRef());
        }
        programs.save(p);
        audit.human(actor, "SCF_PROGRAM_APPROVED", "ScfProgram", p.getScfRef(),
                "Approved%s".formatted(limitRef == null ? "" : " (limit node " + limitRef + ")"),
                Map.of("status", "APPROVED", "registeredLimitRef", limitRef == null ? "" : limitRef));
        return view(p.getScfRef());
    }

    /** PENDING_APPROVAL → REJECTED; SoD (rejecter ≠ raiser) + credit authority. */
    @Transactional
    public ProgramView reject(String scfRef, String note, String actor) {
        ScfProgram p = program(scfRef);
        if (!"PENDING_APPROVAL".equals(p.getStatus())) {
            throw ApiException.conflict("Only a PENDING_APPROVAL programme can be rejected (is " + p.getStatus() + ")");
        }
        guardApprovalAuthority(p, actor);
        p.setStatus("REJECTED");
        p.setDecidedBy(actor);
        p.setDecidedAt(Instant.now());
        p.setDecisionNote(note);
        programs.save(p);
        audit.human(actor, "SCF_PROGRAM_REJECTED", "ScfProgram", p.getScfRef(),
                "Rejected%s".formatted(note == null || note.isBlank() ? "" : ": " + note),
                Map.of("status", "REJECTED"));
        return view(p.getScfRef());
    }

    /** Raiser-only withdrawal, pre-decision (DRAFT or PENDING_APPROVAL). */
    @Transactional
    public ProgramView withdraw(String scfRef, String actor) {
        ScfProgram p = program(scfRef);
        if (!"DRAFT".equals(p.getStatus()) && !"PENDING_APPROVAL".equals(p.getStatus())) {
            throw ApiException.conflict("Only a DRAFT or PENDING_APPROVAL programme can be withdrawn (is " + p.getStatus() + ")");
        }
        if (actor == null || !actor.equals(p.getRaisedBy())) {
            throw ApiException.forbiddenAutonomy(
                    "Only the raiser (" + p.getRaisedBy() + ") may withdraw the programme");
        }
        p.setStatus("WITHDRAWN");
        programs.save(p);
        audit.human(actor, "SCF_PROGRAM_WITHDRAWN", "ScfProgram", p.getScfRef(),
                "Withdrawn by raiser", Map.of("status", "WITHDRAWN"));
        return view(p.getScfRef());
    }

    /** SoD (decider ≠ raiser) + credit-authority gate — {@link ApiException#forbiddenAutonomy} (403) on violation. */
    private void guardApprovalAuthority(ScfProgram p, String actor) {
        if (actor == null || actor.isBlank()) {
            throw ApiException.forbiddenAutonomy("A named human actor is required to decide an SCF programme");
        }
        if (actor.equals(p.getRaisedBy())) {
            throw ApiException.forbiddenAutonomy(
                    "Segregation of duties — the decision must be made by a different actor than the raiser ("
                    + p.getRaisedBy() + ")");
        }
        if (!APPROVAL_AUTHORITIES.contains(actor)) {
            throw ApiException.forbiddenAutonomy(
                    "Actor '" + actor + "' lacks the credit authority to decide an SCF product paper");
        }
    }

    // ------------------------------------------------------------------- read

    @Transactional(readOnly = true)
    public ProgramView view(String scfRef) {
        ScfProgram p = program(scfRef);
        List<ScfSpoke> ss = spokes.findByProgramRefOrderByIdAsc(scfRef);
        long eligible = ss.stream().filter(s -> "PASS".equals(s.getEligibilityResult())).count();
        double requestedTotal = round2(ss.stream().mapToDouble(ScfSpoke::getRequestedAmount).sum());
        double approvedTotal = round2(ss.stream().mapToDouble(ScfSpoke::getApprovedCap).sum());
        return new ProgramView(p, ss, ss.size(), eligible, requestedTotal, approvedTotal);
    }

    @Transactional(readOnly = true)
    public List<ScfProgram> list(String anchorRef) {
        return (anchorRef == null || anchorRef.isBlank())
                ? programs.findAllByOrderByCreatedAtDesc()
                : programs.findByAnchorRefOrderByCreatedAtDesc(anchorRef);
    }

    // ---------------------------------------------------------------- helpers

    private ScfProgram program(String scfRef) {
        return programs.findByScfRef(scfRef)
                .orElseThrow(() -> ApiException.notFound("No SCF programme: " + scfRef));
    }

    private String generateRef() {
        final String alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
        for (int attempt = 0; attempt < 8; attempt++) {
            StringBuilder sb = new StringBuilder("SCF-");
            for (int i = 0; i < 6; i++) {
                sb.append(alphabet.charAt(ThreadLocalRandom.current().nextInt(alphabet.length())));
            }
            String ref = sb.toString();
            if (!programs.existsByScfRef(ref)) return ref;
        }
        throw ApiException.conflict("Could not allocate a unique SCF reference — retry");
    }

    private static double nz(Double d) {
        return d == null ? 0.0 : d;
    }

    private static double num(Map<String, Object> m, String key, double fallback) {
        Object v = m.get(key);
        return v instanceof Number n ? n.doubleValue() : fallback;
    }

    @SuppressWarnings("unchecked")
    private static List<String> strList(Map<String, Object> m, String key) {
        Object v = m.get(key);
        List<String> out = new ArrayList<>();
        if (v instanceof List<?> raw) {
            for (Object o : raw) out.add(String.valueOf(o).toUpperCase());
        }
        return out;
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
