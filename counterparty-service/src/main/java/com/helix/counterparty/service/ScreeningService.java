package com.helix.counterparty.service;

import com.helix.common.audit.AuditService;
import com.helix.common.llm.LlmClient;
import com.helix.common.llm.LlmRequest;
import com.helix.common.llm.LlmResult;
import com.helix.common.model.Enums.ScreeningDisposition;
import com.helix.common.web.ApiException;
import com.helix.counterparty.entity.Counterparty;
import com.helix.counterparty.entity.ScreeningHit;
import com.helix.counterparty.repo.CounterpartyRepository;
import com.helix.counterparty.repo.ScreeningHitRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Sanctions / PEP / adverse-media screening (PRD §1, US-1.2). Hits carry matched
 * attributes, source, score and an AI rationale; the system never auto-clears —
 * every disposition is a named human action, and high-severity hits cannot be
 * cleared without escalation.
 */
@Service
public class ScreeningService {

    private final ScreeningHitRepository hits;
    private final CounterpartyRepository counterparties;
    private final AuditService audit;
    private final LlmClient llm;

    public ScreeningService(ScreeningHitRepository hits, CounterpartyRepository counterparties,
                            AuditService audit, LlmClient llm) {
        this.hits = hits;
        this.counterparties = counterparties;
        this.audit = audit;
        this.llm = llm;
    }

    @Transactional
    public List<ScreeningHit> run(Long counterpartyId, String actor) {
        Counterparty cp = counterparties.findById(counterpartyId)
                .orElseThrow(() -> ApiException.notFound("No counterparty: " + counterpartyId));
        List<ScreeningHit> generated = new ArrayList<>();
        // Models that actually drafted a rationale (only populated when an external LLM is configured).
        List<String> llmModels = new ArrayList<>();

        if (cp.isPep()) {
            generated.add(hit(cp, "PEP", cp.getLegalName(), 0.92, "HIGH",
                    List.of("name", "role:director", "jurisdiction:" + cp.getCountry()), llmModels));
        }
        if (cp.isAdverseMedia()) {
            generated.add(hit(cp, "ADVERSE_MEDIA", cp.getLegalName(), 0.74, "MEDIUM",
                    List.of("name", "topic:regulatory-investigation"), llmModels));
        }
        if (cp.isHighRiskJurisdiction()) {
            generated.add(hit(cp, "OFAC", cp.getLegalName(), 0.68, "HIGH",
                    List.of("name", "country:" + cp.getCountry()), llmModels));
        }
        // A plausible weak name-match every run, to exercise the false-positive path.
        generated.add(hit(cp, "WORLDCHECK", cp.getLegalName(), 0.41, "LOW",
                List.of("name:partial", "no-secondary-identifier"), llmModels));

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("hitCount", generated.size());
        if (!llmModels.isEmpty()) {
            // Only the advisory rationale TEXT was model-drafted; match detection, scores, severity
            // and dispositions stay 100% deterministic. Record which model, for the audit trail.
            detail.put("llmDrafted", true);
            detail.put("llmModel", llmModels.get(llmModels.size() - 1));
        }
        audit.ai("screening-engine", "SCREENING_RUN", "Counterparty", cp.getReference(),
                "Generated %d screening hit(s) for disposition".formatted(generated.size()), detail);
        return generated;
    }

    private ScreeningHit hit(Counterparty cp, String source, String name, double score,
                             String severity, List<String> attrs, List<String> llmModels) {
        ScreeningHit h = new ScreeningHit();
        h.setCounterpartyId(cp.getId());
        h.setListSource(source);
        h.setMatchScore(score);
        h.setSeverity(severity);
        h.setMatchedName(name);
        h.setMatchedAttributes(attrs);
        // Deterministic template rationale is always the fallback (byte-identical when provider=none).
        String template = rationale(source, name, score, attrs);
        RationaleDraft drafted = llmRationale(source, name, score, severity, attrs, template);
        if (drafted != null) {
            h.setAiRationale(drafted.text());
            llmModels.add(drafted.model());
        } else {
            h.setAiRationale(template);
        }
        h.setDisposition(ScreeningDisposition.OPEN.name());
        return hits.save(h);
    }

    /** Grounded rationale citing matched fields — decision-support only (PRD §6.1). */
    private String rationale(String source, String name, double score, List<String> attrs) {
        return "Potential %s match on \"%s\" (match score %.2f). Matched attributes: %s. "
                .formatted(source, name, score, String.join(", ", attrs))
                + "This rationale cites only the matched fields and does not itself clear or confirm the hit; "
                + "a named human must disposition it.";
    }

    /**
     * Advisory LLM rewrite of the screening-hit rationale, grounded ONLY in the deterministic
     * match facts (list/source, matched name, match score, severity, matched attributes and the
     * OPEN disposition context). It narrates WHY the candidate is a potential match; it never
     * changes the match score, severity or disposition, and the hit still requires a named-human
     * disposition. Returns {@code null} when not configured / failed / empty so the caller keeps
     * the deterministic template — fail-soft, byte-identical default.
     */
    private RationaleDraft llmRationale(String source, String name, double score, String severity,
                                        List<String> attrs, String deterministic) {
        String system = "You are drafting an ADVISORY rationale narrative for one sanctions/PEP/adverse-media "
                + "screening match in a wholesale-credit KYC workflow. capability=screening-rationale. In 2-3 "
                + "sentences of plain prose, explain WHY the candidate is a potential match, citing ONLY the match "
                + "facts supplied. Reuse the match score, severity, list source and matched attributes verbatim — "
                + "never invent, estimate or alter any value. Do NOT state that the hit is cleared, confirmed, a "
                + "true or a false positive: this rationale is decision-support only and a named human must "
                + "disposition it.";
        String user = "List/source: " + source
                + "\nCandidate name: " + name
                + "\nMatch score (0..1 — do not change): " + String.format(Locale.UK, "%.2f", score)
                + "\nSeverity (do not change): " + severity
                + "\nMatched attributes: " + String.join(", ", attrs)
                + "\nDisposition status: OPEN (awaiting named-human disposition)"
                + "\nDeterministic rationale for reference (improve the wording, not the facts): " + deterministic;
        LlmResult r = safeComplete(LlmRequest.of("screening-rationale", system, user));
        if (!r.usable()) {
            return null;
        }
        return new RationaleDraft(r.text().strip(), r.model());
    }

    private LlmResult safeComplete(LlmRequest req) {
        try {
            LlmResult r = llm.complete(req);
            return r == null ? LlmResult.notConfigured() : r;
        } catch (Exception e) {
            return LlmResult.failed(e.getMessage());
        }
    }

    /** LLM-drafted rationale text + the model that produced it. */
    private record RationaleDraft(String text, String model) {
    }

    @Transactional(readOnly = true)
    public List<ScreeningHit> forCounterparty(Long counterpartyId) {
        return hits.findByCounterpartyIdOrderBySeverityDesc(counterpartyId);
    }

    @Transactional
    public ScreeningHit disposition(Long hitId, String disposition, String note, String actor) {
        ScreeningHit h = hits.findById(hitId)
                .orElseThrow(() -> ApiException.notFound("No screening hit: " + hitId));
        ScreeningDisposition target = parse(disposition);

        boolean clearing = target == ScreeningDisposition.FALSE_POSITIVE
                || target == ScreeningDisposition.TRUE_POSITIVE_CLEARED;
        if (clearing && CounterpartyService.severityRank(h.getSeverity()) >= CounterpartyService.severityRank("SEVERE")) {
            throw ApiException.forbiddenAutonomy(
                    "SEVERE hits cannot be cleared directly; escalate or exit the relationship first");
        }

        h.setDisposition(target.name());
        h.setDispositionedBy(actor);
        h.setDispositionedAt(Instant.now());
        h.setDispositionNote(note);
        audit.human(actor, "SCREENING_DISPOSITIONED", "ScreeningHit", String.valueOf(hitId),
                "%s hit dispositioned as %s".formatted(h.getListSource(), target),
                Map.of("severity", h.getSeverity(), "disposition", target.name()));
        return hits.save(h);
    }

    private ScreeningDisposition parse(String value) {
        try {
            return ScreeningDisposition.valueOf(value.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("Unknown disposition: " + value);
        }
    }
}
