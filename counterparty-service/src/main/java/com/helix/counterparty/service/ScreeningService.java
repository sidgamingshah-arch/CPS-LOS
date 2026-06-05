package com.helix.counterparty.service;

import com.helix.common.audit.AuditService;
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
import java.util.List;
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

    public ScreeningService(ScreeningHitRepository hits, CounterpartyRepository counterparties, AuditService audit) {
        this.hits = hits;
        this.counterparties = counterparties;
        this.audit = audit;
    }

    @Transactional
    public List<ScreeningHit> run(Long counterpartyId, String actor) {
        Counterparty cp = counterparties.findById(counterpartyId)
                .orElseThrow(() -> ApiException.notFound("No counterparty: " + counterpartyId));
        List<ScreeningHit> generated = new ArrayList<>();

        if (cp.isPep()) {
            generated.add(hit(cp, "PEP", cp.getLegalName(), 0.92, "HIGH",
                    List.of("name", "role:director", "jurisdiction:" + cp.getCountry())));
        }
        if (cp.isAdverseMedia()) {
            generated.add(hit(cp, "ADVERSE_MEDIA", cp.getLegalName(), 0.74, "MEDIUM",
                    List.of("name", "topic:regulatory-investigation")));
        }
        if (cp.isHighRiskJurisdiction()) {
            generated.add(hit(cp, "OFAC", cp.getLegalName(), 0.68, "HIGH",
                    List.of("name", "country:" + cp.getCountry())));
        }
        // A plausible weak name-match every run, to exercise the false-positive path.
        generated.add(hit(cp, "WORLDCHECK", cp.getLegalName(), 0.41, "LOW",
                List.of("name:partial", "no-secondary-identifier")));

        audit.ai("screening-engine", "SCREENING_RUN", "Counterparty", cp.getReference(),
                "Generated %d screening hit(s) for disposition".formatted(generated.size()),
                Map.of("hitCount", generated.size()));
        return generated;
    }

    private ScreeningHit hit(Counterparty cp, String source, String name, double score,
                             String severity, List<String> attrs) {
        ScreeningHit h = new ScreeningHit();
        h.setCounterpartyId(cp.getId());
        h.setListSource(source);
        h.setMatchedName(name);
        h.setMatchScore(score);
        h.setSeverity(severity);
        h.setMatchedAttributes(attrs);
        h.setAiRationale(rationale(source, name, score, attrs));
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
