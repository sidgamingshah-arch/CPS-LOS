package com.helix.decision.service;

import com.helix.common.audit.AuditService;
import com.helix.common.query.QueryService;
import com.helix.common.web.ApiException;
import com.helix.decision.client.UpstreamClient;
import com.helix.decision.client.UpstreamClient.MasterVersionDto;
import com.helix.decision.dto.PerfectionDtos.CaseView;
import com.helix.decision.dto.PerfectionDtos.CreateCaseRequest;
import com.helix.decision.dto.PerfectionDtos.StepActionRequest;
import com.helix.decision.dto.PerfectionDtos.VendorRfqRequest;
import com.helix.decision.entity.PerfectionCase;
import com.helix.decision.entity.PerfectionStep;
import com.helix.decision.repo.PerfectionCaseRepository;
import com.helix.decision.repo.PerfectionStepRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Mortgage / MOE (Memorandum-of-Entry) security-perfection workflow (Wave-2).
 *
 * <p>A {@link PerfectionCase} materialises its ordered {@link PerfectionStep}s from the
 * {@code CHECKLIST_MASTER} row keyed {@code PERFECTION_MOE} (title search → legal opinion →
 * valuation → MOE execution → MOE vetting → CERSAI filing) via the config master client, with a
 * conservative built-in fallback ordered list when the master is absent / config-service is
 * unreachable. Each step is role-gated to its {@code ownerRole}; the MOE-vetting step additionally
 * enforces segregation of duties — the vetting actor must differ from the MOE-execution actor. A
 * VENDOR step raises an EXTERNAL_VENDOR {@link QueryService} thread (the vendor report returns via
 * the existing external-response callback).</p>
 *
 * <p>The workflow also exposes an OPTIONAL, DEFAULT-OFF limit-release gate (see
 * {@link #limitReleaseBlocked(String)}): when — and only when — the DOA_MATRIX config carries
 * {@code perfectionRequired=true} for a flow, a limit release is blocked until a PerfectionCase for
 * that application is COMPLETED. With the key absent (every seeded pack today) the gate is inert.</p>
 */
@Service
public class PerfectionService {

    private static final Logger log = LoggerFactory.getLogger(PerfectionService.class);

    private static final String CHECKLIST_TYPE = "CHECKLIST_MASTER";
    private static final String DEFAULT_KEY = "PERFECTION_MOE";
    private static final String MOE_EXECUTION = "MOE_EXECUTION";
    private static final String MOE_VETTING = "MOE_VETTING";
    private static final List<String> TERMINAL = List.of("DONE", "WAIVED");

    /** Conservative built-in ordered list when the CHECKLIST_MASTER PERFECTION_MOE row is absent. */
    private static final List<String[]> FALLBACK_STEPS = List.of(
            new String[]{"TITLE_SEARCH", "Title search / search report", "LEGAL"},
            new String[]{"LEGAL_OPINION", "Legal opinion on title", "LEGAL"},
            new String[]{"VALUATION", "Property valuation", "VENDOR"},
            new String[]{MOE_EXECUTION, "Memorandum of Entry (MOE) execution", "LMO"},
            new String[]{MOE_VETTING, "MOE vetting", "CAD_OPS"},
            new String[]{"CERSAI_FILING", "CERSAI charge filing", "CAD_OPS"});

    private final PerfectionCaseRepository cases;
    private final PerfectionStepRepository steps;
    private final UpstreamClient upstream;
    private final QueryService queries;
    private final AuditService audit;

    public PerfectionService(PerfectionCaseRepository cases, PerfectionStepRepository steps,
                             UpstreamClient upstream, QueryService queries, AuditService audit) {
        this.cases = cases;
        this.steps = steps;
        this.upstream = upstream;
        this.queries = queries;
        this.audit = audit;
    }

    // =============================================================== create + read

    @Transactional
    public CaseView createCase(CreateCaseRequest req, String actor) {
        PerfectionCase c = new PerfectionCase();
        c.setPerfRef(newRef());
        c.setSubjectType(req.subjectType().trim().toUpperCase());
        c.setSubjectRef(req.subjectRef().trim());
        c.setApplicationRef(blankToNull(req.applicationRef()));
        c.setStatus("OPEN");
        c.setCreatedBy(actor);
        String key = (req.checklistKey() == null || req.checklistKey().isBlank())
                ? DEFAULT_KEY : req.checklistKey().trim();
        c.setChecklistKey(key);

        // Resolve + pin the master version (conservative fallback to the built-in list).
        List<String[]> resolved;
        MasterVersionDto master = upstream.masterActive(CHECKLIST_TYPE, key);
        if (master != null) {
            c.setMasterVersion(master.version());
            resolved = readSteps(master.payload());
            if (resolved.isEmpty()) resolved = FALLBACK_STEPS;
        } else {
            c.setMasterVersion(null);       // built-in fallback used
            resolved = FALLBACK_STEPS;
        }
        PerfectionCase saved = cases.save(c);

        int order = 0;
        for (String[] s : resolved) {
            PerfectionStep step = new PerfectionStep();
            step.setCaseRef(saved.getPerfRef());
            step.setStepKey(s[0]);
            step.setTitle(s[1]);
            step.setOwnerRole(s[2]);
            step.setStatus("PENDING");
            step.setStepOrder(order++);
            steps.save(step);
        }
        audit.human(actor, "PERFECTION_CASE_CREATED", "PerfectionCase", saved.getPerfRef(),
                "Opened MOE-perfection case for %s %s with %d step(s) from %s%s".formatted(
                        saved.getSubjectType(), saved.getSubjectRef(), resolved.size(), key,
                        master == null ? " (built-in fallback)" : " v" + master.version()),
                Map.of("subjectType", saved.getSubjectType(), "subjectRef", saved.getSubjectRef(),
                        "checklistKey", key, "steps", resolved.size(),
                        "masterVersion", String.valueOf(saved.getMasterVersion())));
        return view(saved.getPerfRef());
    }

    @Transactional(readOnly = true)
    public List<PerfectionCase> list(String subjectRef, String status) {
        if (subjectRef != null && !subjectRef.isBlank()) return cases.findBySubjectRefOrderByIdDesc(subjectRef);
        if (status != null && !status.isBlank()) return cases.findByStatusOrderByIdDesc(status.trim().toUpperCase());
        return cases.findAllByOrderByIdDesc();
    }

    @Transactional(readOnly = true)
    public CaseView view(String perfRef) {
        PerfectionCase c = require(perfRef);
        return new CaseView(c, steps.findByCaseRefOrderByStepOrderAsc(c.getPerfRef()));
    }

    // =============================================================== step transitions

    @Transactional
    public CaseView completeStep(String perfRef, String stepKey, StepActionRequest req, String actor) {
        return actOnStep(perfRef, stepKey, req, actor, "DONE");
    }

    @Transactional
    public CaseView waiveStep(String perfRef, String stepKey, StepActionRequest req, String actor) {
        return actOnStep(perfRef, stepKey, req, actor, "WAIVED");
    }

    private CaseView actOnStep(String perfRef, String stepKey, StepActionRequest req, String actor,
                               String targetStatus) {
        PerfectionCase c = require(perfRef);
        if ("CANCELLED".equals(c.getStatus())) {
            throw ApiException.conflict("Perfection case " + perfRef + " is CANCELLED");
        }
        PerfectionStep step = steps.findByCaseRefAndStepKey(perfRef, stepKey)
                .orElseThrow(() -> ApiException.notFound("No step '" + stepKey + "' on " + perfRef));
        if (TERMINAL.contains(step.getStatus())) {
            throw ApiException.conflict("Step " + stepKey + " is already " + step.getStatus());
        }
        // Role gate — the acting role context must match the step's owner role.
        requireRole(step, req == null ? null : req.role());
        // MOE-vetting SoD — the vetting actor must differ from the MOE-execution actor.
        if (MOE_VETTING.equalsIgnoreCase(step.getStepKey())) {
            enforceVettingSoD(perfRef, actor);
        }

        step.setStatus(targetStatus);
        step.setCompletedBy(actor);
        step.setCompletedAt(Instant.now());
        if (req != null) {
            step.setEvidence(req.evidence());
            step.setNotes(req.notes());
        }
        steps.save(step);
        audit.human(actor, targetStatus.equals("WAIVED") ? "PERFECTION_STEP_WAIVED" : "PERFECTION_STEP_DONE",
                "PerfectionCase", perfRef,
                "%s '%s' (%s) by %s".formatted(targetStatus.equals("WAIVED") ? "Waived" : "Completed",
                        step.getTitle(), step.getOwnerRole(), actor),
                Map.of("stepKey", stepKey, "ownerRole", step.getOwnerRole(), "status", targetStatus));

        recomputeStatus(c);
        return view(perfRef);
    }

    // =============================================================== vendor RFQ (Query module)

    @Transactional
    public CaseView vendorRfq(String perfRef, String stepKey, VendorRfqRequest req, String actor) {
        PerfectionCase c = require(perfRef);
        PerfectionStep step = steps.findByCaseRefAndStepKey(perfRef, stepKey)
                .orElseThrow(() -> ApiException.notFound("No step '" + stepKey + "' on " + perfRef));
        if (!"VENDOR".equalsIgnoreCase(step.getOwnerRole())) {
            throw ApiException.badRequest("Step " + stepKey + " is owned by " + step.getOwnerRole()
                    + " — only a VENDOR step raises an external-vendor RFQ");
        }
        if (TERMINAL.contains(step.getStatus())) {
            throw ApiException.conflict("Step " + stepKey + " is already " + step.getStatus());
        }
        String vendor = (req != null && req.vendorId() != null && !req.vendorId().isBlank())
                ? req.vendorId().trim() : "vendor";
        String question = (req != null && req.question() != null && !req.question().isBlank())
                ? req.question().trim()
                : "Please provide the report for '" + step.getTitle() + "' on " + safe(c.getSubjectRef()) + ".";
        QueryService.Raise cmd = new QueryService.Raise("EXTERNAL_VENDOR", "PerfectionStep", perfRef,
                "Perfection RFQ: " + step.getTitle(), question, vendor, "vendor",
                null, null, null, null, null, List.of("vendor"), null);
        QueryService.View qv = queries.raise(cmd, actor);
        step.setVendorQueryRef(qv.thread().getQueryRef());
        steps.save(step);
        // First operational action on the case moves it off OPEN.
        if ("OPEN".equals(c.getStatus())) {
            c.setStatus("IN_PROGRESS");
            cases.save(c);
        }
        audit.human(actor, "PERFECTION_VENDOR_RFQ", "PerfectionCase", perfRef,
                "Vendor RFQ raised for '%s' to '%s' (query %s)".formatted(
                        step.getTitle(), vendor, qv.thread().getQueryRef()),
                Map.of("stepKey", stepKey, "vendorRef", vendor, "queryRef", qv.thread().getQueryRef()));
        return view(perfRef);
    }

    // =============================================================== limit-release gate (DEFAULT-OFF)

    /**
     * OPTIONAL, DEFAULT-OFF gate consulted by CAD limit-release. Returns {@code true} (block) ONLY
     * when the DOA_MATRIX config carries {@code perfectionRequired=true} for the application's
     * flow AND no PerfectionCase for that application is COMPLETED. With the key absent — every
     * seeded pack today — this returns {@code false} and limit-release behaviour is unchanged.
     * Fully guarded: any lookup failure resolves to {@code false} (OFF), never blocks.
     */
    @Transactional(readOnly = true)
    public boolean limitReleaseBlocked(String applicationRef) {
        if (applicationRef == null || applicationRef.isBlank()) return false;
        boolean required;
        try {
            UpstreamClient.DealEnvelopeDto env = upstream.envelopeOrNull(applicationRef);
            String jurisdiction = env == null ? null : env.jurisdiction();
            required = upstream.perfectionRequired(jurisdiction);
        } catch (Exception e) {
            log.warn("perfection gate lookup failed for {} ({}) — gate OFF", applicationRef, e.getMessage());
            return false;
        }
        if (!required) return false;   // key absent/false ⇒ identical behaviour to before
        return !cases.existsByApplicationRefAndStatus(applicationRef, "COMPLETED");
    }

    // =============================================================== internals

    private void requireRole(PerfectionStep step, String declaredRole) {
        if (declaredRole == null || declaredRole.isBlank()
                || !declaredRole.trim().equalsIgnoreCase(step.getOwnerRole())) {
            throw ApiException.forbiddenAutonomy("Step '" + step.getStepKey() + "' is owned by role "
                    + step.getOwnerRole() + " — actor declared role '" + declaredRole + "'");
        }
    }

    private void enforceVettingSoD(String perfRef, String actor) {
        PerfectionStep exec = steps.findByCaseRefAndStepKey(perfRef, MOE_EXECUTION).orElse(null);
        if (exec != null && exec.getCompletedBy() != null
                && exec.getCompletedBy().equalsIgnoreCase(actor)) {
            throw ApiException.forbiddenAutonomy("MOE vetting must be performed by someone other than the "
                    + "MOE-execution actor '" + exec.getCompletedBy() + "' (segregation of duties)");
        }
    }

    private void recomputeStatus(PerfectionCase c) {
        List<PerfectionStep> all = steps.findByCaseRefOrderByStepOrderAsc(c.getPerfRef());
        boolean allTerminal = !all.isEmpty() && all.stream().allMatch(s -> TERMINAL.contains(s.getStatus()));
        String next = allTerminal ? "COMPLETED" : "IN_PROGRESS";
        if (!next.equals(c.getStatus())) {
            c.setStatus(next);
            cases.save(c);
            if ("COMPLETED".equals(next)) {
                audit.human(c.getCreatedBy() == null ? "system" : c.getCreatedBy(),
                        "PERFECTION_CASE_COMPLETED", "PerfectionCase", c.getPerfRef(),
                        "All perfection steps DONE/WAIVED", Map.of("steps", all.size()));
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static List<String[]> readSteps(Map<String, Object> payload) {
        List<String[]> out = new ArrayList<>();
        if (payload == null) return out;
        Object raw = payload.get("steps");
        if (!(raw instanceof List<?> list)) return out;
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> m)) continue;
            String key = str(((Map<String, Object>) m).get("key"));
            String title = str(((Map<String, Object>) m).get("title"));
            String role = str(((Map<String, Object>) m).get("ownerRole"));
            if (key.isBlank()) continue;
            out.add(new String[]{key, title.isBlank() ? key : title, role.isBlank() ? "CAD_OPS" : role.toUpperCase()});
        }
        return out;
    }

    private PerfectionCase require(String perfRef) {
        return cases.findByPerfRef(perfRef)
                .orElseThrow(() -> ApiException.notFound("Perfection case " + perfRef + " not found"));
    }

    private static String newRef() {
        final String alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
        StringBuilder sb = new StringBuilder("PRF-");
        for (int i = 0; i < 6; i++) {
            sb.append(alphabet.charAt(ThreadLocalRandom.current().nextInt(alphabet.length())));
        }
        return sb.toString();
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o).trim();
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }
}
