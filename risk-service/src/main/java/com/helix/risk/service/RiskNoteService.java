package com.helix.risk.service;

import com.helix.common.audit.AuditService;
import com.helix.common.governance.AiCapability;
import com.helix.common.governance.AiGovernanceClient;
import com.helix.common.llm.LlmClient;
import com.helix.common.llm.LlmRequest;
import com.helix.common.llm.LlmResult;
import com.helix.common.web.ApiException;
import com.helix.risk.client.OriginationClient;
import com.helix.risk.dto.RiskNoteDtos.CreateRiskNoteRequest;
import com.helix.risk.dto.RiskNoteDtos.UpdateSectionsRequest;
import com.helix.risk.entity.Rating;
import com.helix.risk.entity.RiskNote;
import com.helix.risk.entity.RiskNote.RecommendedAction;
import com.helix.risk.entity.RiskNote.Status;
import com.helix.risk.repo.RatingRepository;
import com.helix.risk.repo.RiskNoteRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The Independent Risk Note engine (CLoM R1-13). The risk function authors its own
 * qualitative opinion on a credit, then drives it through a governed lifecycle:
 * DRAFT → SUBMITTED → REVIEWED → APPROVED, with REJECTED / REVERSED terminals and a
 * reassign action for the work-item owner.
 *
 * <p><b>Advisory invariant.</b> A risk note is an OPINION record. It reads the
 * authoritative {@link Rating} to snapshot the grade/PD it forms an opinion about, but
 * it makes no write to the rating (or any other authoritative figure). Every gate is a
 * named-human action ({@code audit.human}); an optional AI section draft is stamped
 * {@code audit.ai} and is fail-soft (no external model → the human authors verbatim).
 * Segregation of duties: the reviewer/approver must differ from the author.</p>
 */
@Service
public class RiskNoteService {

    /** Canonical narrative sections of an independent risk note. */
    static final List<String> SECTION_KEYS = List.of("RISK_OPINION", "KEY_RISKS", "MITIGANTS", "RECOMMENDATION");

    private static final Map<String, String> SECTION_BRIEF = Map.of(
            "RISK_OPINION", "the independent risk view on this credit overall",
            "KEY_RISKS", "the principal risks (financial, business, structural, ESG)",
            "MITIGANTS", "the mitigants and structural protections that offset those risks",
            "RECOMMENDATION", "the risk function's recommendation and any conditions");

    private final RiskNoteRepository notes;
    private final RatingRepository ratings;
    private final OriginationClient origination;
    private final AuditService audit;
    private final AiGovernanceClient governance;
    private final LlmClient llm;

    public RiskNoteService(RiskNoteRepository notes, RatingRepository ratings, OriginationClient origination,
                           AuditService audit, AiGovernanceClient governance, LlmClient llm) {
        this.notes = notes;
        this.ratings = ratings;
        this.origination = origination;
        this.audit = audit;
        this.governance = governance;
        this.llm = llm;
    }

    // ---- create / read ----

    @Transactional
    public RiskNote create(CreateRiskNoteRequest req, String actor) {
        requireActor(actor);
        RiskNote n = new RiskNote();
        n.setRiskNoteRef(generateRef());
        n.setSubjectRef(req.subjectRef().trim());
        n.setStatus(Status.DRAFT);
        n.setAuthor(actor);
        n.setAssignedTo(actor);
        n.setAdvisory(true);
        n.setSections(normaliseSections(req.sections()));
        n.setRecommendedAction(parseAction(req.recommendedAction()));

        // Snapshot the authoritative rating the opinion is formed against (READ-ONLY — never written back).
        Rating rating = ratings.findFirstByApplicationReferenceOrderByCreatedAtDesc(n.getSubjectRef()).orElse(null);
        if (rating != null) {
            n.setGradeSnapshot(rating.getFinalGrade());
            n.setPdSnapshot(rating.getPd());
        }
        RiskNote saved = notes.save(n);

        audit.human(actor, "RISK_NOTE_CREATED", "Application", saved.getSubjectRef(),
                "Independent risk note %s raised for %s".formatted(saved.getRiskNoteRef(), saved.getSubjectRef()),
                Map.of("riskNoteRef", saved.getRiskNoteRef(), "subjectRef", saved.getSubjectRef(),
                        "advisory", true, "gradeSnapshot", String.valueOf(saved.getGradeSnapshot())));
        return saved;
    }

    @Transactional(readOnly = true)
    public RiskNote get(String ref) {
        return notes.findByRiskNoteRef(ref)
                .orElseThrow(() -> ApiException.notFound("No risk note: " + ref));
    }

    @Transactional(readOnly = true)
    public List<RiskNote> list(String subjectRef) {
        if (subjectRef != null && !subjectRef.isBlank()) {
            return notes.findBySubjectRefOrderByIdDesc(subjectRef.trim());
        }
        return notes.findAllByOrderByIdDesc();
    }

    // ---- authoring ----

    /**
     * Author / update the narrative sections while the note is a DRAFT. When
     * {@code aiDraft} is requested and a governed external model is configured, any
     * blank section is drafted by the LLM (advisory, grounded on the authoritative
     * grade/PD, stamped {@code audit.ai}); the human-supplied sections always stand.
     */
    @Transactional
    public RiskNote updateSections(String ref, UpdateSectionsRequest req, String actor) {
        requireActor(actor);
        RiskNote n = get(ref);
        if (n.getStatus() != Status.DRAFT) {
            throw ApiException.conflict("Only a DRAFT risk note can be edited (is " + n.getStatus() + ")");
        }
        Map<String, Object> merged = new LinkedHashMap<>(n.getSections() == null ? Map.of() : n.getSections());
        if (req.sections() != null) {
            req.sections().forEach((k, v) -> {
                if (SECTION_KEYS.contains(k)) merged.put(k, v);
            });
        }
        if (req.recommendedAction() != null && !req.recommendedAction().isBlank()) {
            n.setRecommendedAction(parseAction(req.recommendedAction()));
        }

        List<String> aiDrafted = new ArrayList<>();
        if (Boolean.TRUE.equals(req.aiDraft())) {
            aiDrafted = aiDraftBlankSections(n, merged);
        }
        n.setSections(merged);
        RiskNote saved = notes.save(n);

        audit.human(actor, "RISK_NOTE_SECTIONS_UPDATED", "Application", saved.getSubjectRef(),
                "Sections authored on risk note %s".formatted(saved.getRiskNoteRef()),
                Map.of("riskNoteRef", saved.getRiskNoteRef(), "sections", new ArrayList<>(merged.keySet()),
                        "aiDrafted", aiDrafted));
        return saved;
    }

    // ---- workflow transitions ----

    /** DRAFT → SUBMITTED. The author submits their note for independent review. */
    @Transactional
    public RiskNote submit(String ref, String actor) {
        requireActor(actor);
        RiskNote n = get(ref);
        if (n.getStatus() != Status.DRAFT) {
            throw ApiException.conflict("Only a DRAFT risk note can be submitted (is " + n.getStatus() + ")");
        }
        n.setStatus(Status.SUBMITTED);
        n.setSubmittedAt(Instant.now());
        RiskNote saved = notes.save(n);
        audit.human(actor, "RISK_NOTE_SUBMITTED", "Application", saved.getSubjectRef(),
                "Risk note %s submitted for review".formatted(saved.getRiskNoteRef()),
                Map.of("riskNoteRef", saved.getRiskNoteRef(), "status", saved.getStatus().name()));
        return saved;
    }

    /** SUBMITTED → REVIEWED. SoD: the reviewer must differ from the author. */
    @Transactional
    public RiskNote review(String ref, String actor) {
        requireActor(actor);
        RiskNote n = get(ref);
        if (n.getStatus() != Status.SUBMITTED) {
            throw ApiException.conflict("Only a SUBMITTED risk note can be reviewed (is " + n.getStatus() + ")");
        }
        requireDifferentFromAuthor(n, actor, "review");
        n.setReviewer(actor);
        n.setReviewedAt(Instant.now());
        n.setStatus(Status.REVIEWED);
        RiskNote saved = notes.save(n);
        audit.human(actor, "RISK_NOTE_REVIEWED", "Application", saved.getSubjectRef(),
                "Risk note %s reviewed by %s".formatted(saved.getRiskNoteRef(), actor),
                Map.of("riskNoteRef", saved.getRiskNoteRef(), "reviewer", actor));
        return saved;
    }

    /** REVIEWED → APPROVED. SoD: the approver must differ from the author. */
    @Transactional
    public RiskNote approve(String ref, String note, String actor) {
        requireActor(actor);
        RiskNote n = get(ref);
        if (n.getStatus() != Status.REVIEWED) {
            throw ApiException.conflict("Only a REVIEWED risk note can be approved (is " + n.getStatus() + ")");
        }
        requireDifferentFromAuthor(n, actor, "approve");
        if (n.getReviewer() == null || n.getReviewer().isBlank()) {
            n.setReviewer(actor);
        }
        n.setStatus(Status.APPROVED);
        n.setDecidedAt(Instant.now());
        n.setDecisionNote(note);
        RiskNote saved = notes.save(n);
        audit.human(actor, "RISK_NOTE_APPROVED", "Application", saved.getSubjectRef(),
                "Risk note %s approved by %s".formatted(saved.getRiskNoteRef(), actor),
                Map.of("riskNoteRef", saved.getRiskNoteRef(), "approver", actor,
                        "recommendedAction", String.valueOf(saved.getRecommendedAction())));
        return saved;
    }

    /** SUBMITTED | REVIEWED → REJECTED. SoD (approver != author) + mandatory reason. */
    @Transactional
    public RiskNote reject(String ref, String reason, String actor) {
        requireActor(actor);
        RiskNote n = get(ref);
        if (n.getStatus() != Status.SUBMITTED && n.getStatus() != Status.REVIEWED) {
            throw ApiException.conflict("Only a SUBMITTED or REVIEWED risk note can be rejected (is " + n.getStatus() + ")");
        }
        if (reason == null || reason.isBlank()) {
            throw ApiException.badRequest("A rejection reason is mandatory");
        }
        requireDifferentFromAuthor(n, actor, "reject");
        n.setStatus(Status.REJECTED);
        n.setReviewer(actor);
        n.setDecidedAt(Instant.now());
        n.setDecisionNote(reason);
        RiskNote saved = notes.save(n);
        audit.human(actor, "RISK_NOTE_REJECTED", "Application", saved.getSubjectRef(),
                "Risk note %s rejected by %s: %s".formatted(saved.getRiskNoteRef(), actor, reason),
                Map.of("riskNoteRef", saved.getRiskNoteRef(), "reason", reason));
        return saved;
    }

    /** Reassign the work-item owner (non-terminal states only). */
    @Transactional
    public RiskNote reassign(String ref, String toActor, String actor) {
        requireActor(actor);
        if (toActor == null || toActor.isBlank()) {
            throw ApiException.badRequest("A target actor (toActor) is mandatory to reassign");
        }
        RiskNote n = get(ref);
        if (n.getStatus() == Status.APPROVED || n.getStatus() == Status.REJECTED || n.getStatus() == Status.REVERSED) {
            throw ApiException.conflict("A " + n.getStatus() + " risk note cannot be reassigned");
        }
        String from = n.getAssignedTo();
        n.setAssignedTo(toActor.trim());
        RiskNote saved = notes.save(n);
        audit.human(actor, "RISK_NOTE_REASSIGNED", "Application", saved.getSubjectRef(),
                "Risk note %s reassigned %s → %s by %s".formatted(saved.getRiskNoteRef(), from, saved.getAssignedTo(), actor),
                Map.of("riskNoteRef", saved.getRiskNoteRef(), "from", String.valueOf(from), "to", saved.getAssignedTo()));
        return saved;
    }

    /** APPROVED → REVERSED. Mandatory reason. A reversal is a record, never a figure change. */
    @Transactional
    public RiskNote reverse(String ref, String reason, String actor) {
        requireActor(actor);
        RiskNote n = get(ref);
        if (n.getStatus() != Status.APPROVED) {
            throw ApiException.conflict("Only an APPROVED risk note can be reversed (is " + n.getStatus() + ")");
        }
        if (reason == null || reason.isBlank()) {
            throw ApiException.badRequest("A reversal reason is mandatory");
        }
        n.setStatus(Status.REVERSED);
        n.setReversedAt(Instant.now());
        n.setDecisionNote(reason);
        RiskNote saved = notes.save(n);
        audit.human(actor, "RISK_NOTE_REVERSED", "Application", saved.getSubjectRef(),
                "Risk note %s reversed by %s: %s".formatted(saved.getRiskNoteRef(), actor, reason),
                Map.of("riskNoteRef", saved.getRiskNoteRef(), "reason", reason, "reversedBy", actor));
        return saved;
    }

    // ---- helpers ----

    private void requireActor(String actor) {
        if (actor == null || actor.isBlank()) {
            throw ApiException.forbiddenAutonomy("A named human actor is required to act on a risk note");
        }
    }

    private void requireDifferentFromAuthor(RiskNote n, String actor, String action) {
        if (actor != null && actor.equalsIgnoreCase(n.getAuthor())) {
            throw ApiException.forbiddenAutonomy(
                    "The " + action + "er cannot be the author ('" + n.getAuthor()
                    + "') — segregation of duties on an independent risk note");
        }
    }

    private Map<String, Object> normaliseSections(Map<String, Object> in) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (in != null) {
            in.forEach((k, v) -> {
                if (SECTION_KEYS.contains(k)) out.put(k, v);
            });
        }
        return out;
    }

    private RecommendedAction parseAction(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return RecommendedAction.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("Unknown recommendedAction: " + value
                    + " (expected SUPPORT | SUPPORT_WITH_CONDITIONS | DECLINE)");
        }
    }

    private String generateRef() {
        for (int i = 0; i < 8; i++) {
            String ref = "RN-" + UUID.randomUUID().toString().replace("-", "").substring(0, 6).toUpperCase();
            if (!notes.existsByRiskNoteRef(ref)) {
                return ref;
            }
        }
        return "RN-" + UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase();
    }

    // ---- optional advisory AI section draft (governed LlmClient boundary, fail-soft) ----

    /**
     * Drafts any blank canonical section via the governed LLM boundary, grounded on the
     * authoritative grade/PD snapshot. Prose only — it never invents or changes a figure,
     * and never touches the authoritative rating. Fail-soft: provider {@code none}
     * (default) / an outage leaves the sections exactly as the human left them. Returns
     * the list of section keys the model drafted (empty when nothing was drafted).
     */
    private List<String> aiDraftBlankSections(RiskNote n, Map<String, Object> sections) {
        governance.enforce(AiCapability.COMMENTARY, resolveJurisdiction(n.getSubjectRef()));
        List<String> drafted = new ArrayList<>();
        String model = null;
        String grade = n.getGradeSnapshot();
        double pd = n.getPdSnapshot();
        for (String key : SECTION_KEYS) {
            Object existing = sections.get(key);
            if (existing != null && !String.valueOf(existing).isBlank()) {
                continue;   // human-authored — never overwritten
            }
            LlmResult r = safeComplete(LlmRequest.of("risk-note",
                    "You are drafting one ADVISORY section of an INDEPENDENT RISK NOTE for a wholesale credit. "
                    + "capability=risk-note. Draft " + SECTION_BRIEF.getOrDefault(key, key.toLowerCase())
                    + ". Use ONLY the authoritative figures provided; never invent, estimate or change any value, "
                    + "and state that this note is advisory and does NOT change the authoritative rating. "
                    + "Reply with 2-4 sentences of plain prose.",
                    "Section: " + key + "\nAuthoritative grade: " + (grade == null ? "n/a" : grade)
                    + "\nAuthoritative PD: " + pd
                    + "\nRecommended action: " + (n.getRecommendedAction() == null ? "n/a" : n.getRecommendedAction())));
            if (r.usable()) {
                sections.put(key, r.text().strip());
                drafted.add(key);
                model = r.model();
            }
        }
        if (!drafted.isEmpty()) {
            audit.ai("risk-note", "RISK_NOTE_AI_DRAFTED", "Application", n.getSubjectRef(),
                    "AI drafted %d advisory section(s) of risk note %s — non-binding, rating unchanged"
                            .formatted(drafted.size(), n.getRiskNoteRef()),
                    Map.of("riskNoteRef", n.getRiskNoteRef(), "drafted", drafted,
                            "llmModel", String.valueOf(model), "advisory", true));
        }
        return drafted;
    }

    /** Best-effort jurisdiction lookup for the governance gate; null → default policy. */
    private String resolveJurisdiction(String subjectRef) {
        try {
            return origination.creditInputs(subjectRef).jurisdiction();
        } catch (Exception e) {
            return null;
        }
    }

    private LlmResult safeComplete(LlmRequest req) {
        try {
            LlmResult r = llm.complete(req);
            return r == null ? LlmResult.notConfigured() : r;
        } catch (Exception e) {
            return LlmResult.failed(e.getMessage());
        }
    }
}
