package com.helix.portfolio.service;

import com.helix.common.audit.AuditService;
import com.helix.common.llm.LlmClient;
import com.helix.common.llm.LlmRequest;
import com.helix.common.llm.LlmResult;
import com.helix.common.model.Enums.SignalSeverity;
import com.helix.common.notify.NotificationService;
import com.helix.common.web.ApiException;
import com.helix.portfolio.client.PortfolioUpstreamClient;
import com.helix.portfolio.client.PortfolioUpstreamClient.CovenantDto;
import com.helix.portfolio.client.PortfolioUpstreamClient.CreditInputsDto;
import com.helix.portfolio.entity.EwsSignal;
import com.helix.portfolio.entity.ExposureRecord;
import com.helix.portfolio.repo.EwsSignalRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Agentic early-warning (PRD §11/§6.5). Scans internal + external signals and
 * ranks watch candidates autonomously [A], but only flags and proposes — it
 * cannot reclassify, re-stage or change limits. Humans decide.
 */
@Service
public class EwsService {

    private final EwsSignalRepository signals;
    private final PortfolioUpstreamClient upstream;
    private final AuditService audit;
    private final NotificationService notifications;
    private final LlmClient llm;

    public EwsService(EwsSignalRepository signals, PortfolioUpstreamClient upstream, AuditService audit,
                      NotificationService notifications, LlmClient llm) {
        this.signals = signals;
        this.upstream = upstream;
        this.audit = audit;
        this.notifications = notifications;
        this.llm = llm;
    }

    @Transactional
    public List<EwsSignal> scan(ExposureRecord exp) {
        // Replace prior signals for this exposure to avoid duplicates on re-scan.
        signals.deleteAll(signals.findByApplicationReferenceOrderByScoreDesc(exp.getApplicationReference()));

        CreditInputsDto inputs = upstream.creditInputs(exp.getApplicationReference());
        List<CovenantDto> covenants = upstream.covenants(exp.getApplicationReference());
        Map<String, Double> ratios = inputs.ratios() == null ? Map.of() : inputs.ratios();
        Map<String, PortfolioUpstreamClient.EwsTriggerDto> triggers = upstream.ewsTriggers();

        List<EwsSignal> raised = new ArrayList<>();

        // 1) Covenant breaches (internal).
        for (CovenantDto cov : covenants) {
            if (!cov.active()) {
                continue;
            }
            double observed = ratios.getOrDefault(cov.metric(), 0.0);
            if (!satisfies(observed, cov.operator(), cov.threshold())) {
                raised.add(build(exp, "COVENANT_BREACH", mapSeverity(cov.breachSeverity()), "INTERNAL", 0.85,
                        "Covenant %s %s %.2f breached — observed %.2f (source: management accounts)"
                                .formatted(cov.metric(), cov.operator(), cov.threshold(), observed),
                        "Raise to watchlist and trigger review (human-decided)"));
            }
        }

        // 2) Leverage / coverage stress (internal) — thresholds from the EWS_TRIGGER master,
        //    built-in constants as fallback (early-warning must not go silent if config is down).
        var levT = triggers.get("NET_LEVERAGE");
        double levThreshold = levT != null ? levT.amberThreshold(4.0) : 4.0;
        double netLeverage = ratios.getOrDefault("NET_LEVERAGE", 0.0);
        if ((levT == null || levT.enabled()) && netLeverage > levThreshold) {
            raised.add(build(exp, "LEVERAGE_SPIKE", SignalSeverity.MEDIUM, "INTERNAL",
                    Math.min(0.5 + (netLeverage - levThreshold) / 10.0, 0.9),
                    "Net leverage %.1fx above %.1fx watch level".formatted(netLeverage, levThreshold),
                    "Refresh financials; assess for watchlist"));
        }
        var dscrT = triggers.get("DSCR");
        double dscrThreshold = dscrT != null ? dscrT.amberThreshold(1.1) : 1.1;
        double dscr = ratios.getOrDefault("DSCR", 99.0);
        if ((dscrT == null || dscrT.enabled()) && dscr < dscrThreshold) {
            raised.add(build(exp, "DSCR_PRESSURE", SignalSeverity.HIGH, "INTERNAL", 0.8,
                    "DSCR %.2f below %.2fx — debt-service pressure".formatted(dscr, dscrThreshold),
                    "Engage RM; consider review trigger"));
        }

        // 3) Delinquency (internal/conduct) — watch/severe DPD cutpoints from the EWS_TRIGGER master.
        var dpdT = triggers.get("DPD");
        int dpdWatch = dpdT != null ? (int) dpdT.amberThreshold(30) : 30;
        int dpdSevere = dpdT != null ? (int) dpdT.redThreshold(90) : 90;
        int dpd = exp.getDaysPastDue();
        if ((dpdT == null || dpdT.enabled()) && dpd >= dpdWatch) {
            boolean severe = dpd >= dpdSevere;
            SignalSeverity sev = severe ? SignalSeverity.SEVERE : SignalSeverity.HIGH;
            raised.add(build(exp, "DAYS_PAST_DUE", sev, "INTERNAL", severe ? 0.95 : 0.7,
                    "%d days past due".formatted(dpd),
                    "Candidate for stage migration — staging is human-decided, not auto-applied"));
        }

        // 4) Weak grade (external/rating drift proxy).
        if (List.of("CCC", "CC", "C", "D").contains(exp.getFinalGrade())) {
            raised.add(build(exp, "SUB_INVESTMENT_GRADE", SignalSeverity.HIGH, "EXTERNAL", 0.72,
                    "Current grade %s is deep sub-investment grade".formatted(exp.getFinalGrade()),
                    "Watchlist candidate; review remediation options"));
        }

        // Optional advisory LLM narrative: when a bank has configured an external model, it redrafts each
        // signal's rationale as a richer context summary grounded in the SAME deterministic facts (type,
        // severity, source, score, deterministic rationale, proposed action). It only rewrites the advisory
        // rationale TEXT — the deterministic trigger, severity, score and proposed action are untouched, and
        // staging / reclassification stay human-decided. Provider 'none' (default) → no external call, the
        // deterministic rationale is byte-identical to today.
        boolean llmDrafted = false;
        String llmModel = null;
        for (EwsSignal s : raised) {
            LlmNarrative d = llmEwsNarrative(exp, s);
            if (d != null) {
                s.setRationale(d.text());
                llmDrafted = true;
                llmModel = d.model();
            }
        }

        List<EwsSignal> saved = signals.saveAll(raised);
        Map<String, Object> detail;
        if (llmDrafted) {
            detail = new LinkedHashMap<>();
            detail.put("signalCount", saved.size());
            detail.put("llmDrafted", true);
            detail.put("llmModel", llmModel);
        } else {
            detail = Map.of("signalCount", saved.size());
        }
        audit.ai("ews-agent", "EWS_SCAN", "Application", exp.getApplicationReference(),
                "Scan raised %d signal(s); flags only — no autonomous reclassification".formatted(saved.size()),
                detail);
        // A SEVERE/HIGH signal is worth notifying the desk about — deterministic, SYSTEM-actor
        // (the notification is not an AI decision; the advisory EWS flag it reports is unchanged).
        for (EwsSignal s : saved) {
            if (!"SEVERE".equals(s.getSeverity()) && !"HIGH".equals(s.getSeverity())) continue;
            try {
                notifications.enqueue(new NotificationService.Enqueue("EWS_BREACH", "EWS_BREACH",
                        "Application", exp.getApplicationReference(),
                        "ews:" + exp.getApplicationReference() + ":" + s.getSignalType(), exp.getJurisdiction(),
                        Map.of("borrower", exp.getCounterpartyName() == null ? exp.getApplicationReference()
                                : exp.getCounterpartyName(), "reference", exp.getApplicationReference(),
                                "signalType", s.getSignalType(), "severity", s.getSeverity(),
                                "rationale", s.getRationale() == null ? "" : s.getRationale()), null), "ews.agent");
            } catch (Exception e) {
                // notification failures never break the scan
            }
        }
        return saved;
    }

    @Transactional(readOnly = true)
    public List<EwsSignal> watchlist() {
        return signals.findByStatusOrderByScoreDesc("OPEN");
    }

    @Transactional(readOnly = true)
    public List<EwsSignal> forApplication(String reference) {
        return signals.findByApplicationReferenceOrderByScoreDesc(reference);
    }

    @Transactional
    public EwsSignal disposition(Long id, String status, String actor) {
        EwsSignal s = signals.findById(id).orElseThrow(() -> ApiException.notFound("No signal: " + id));
        String target = status.toUpperCase();
        if (!List.of("REVIEWED", "DISMISSED", "OPEN").contains(target)) {
            throw ApiException.badRequest("status must be REVIEWED, DISMISSED or OPEN");
        }
        s.setStatus(target);
        s.setReviewedBy(actor);
        s.setReviewedAt(Instant.now());
        audit.human(actor, "EWS_SIGNAL_DISPOSITIONED", "Application", s.getApplicationReference(),
                "Signal %s (%s) marked %s".formatted(s.getSignalType(), s.getSeverity(), target),
                Map.of("signalType", s.getSignalType(), "status", target));
        return signals.save(s);
    }

    private EwsSignal build(ExposureRecord exp, String type, SignalSeverity severity, String source,
                            double score, String rationale, String proposedAction) {
        EwsSignal s = new EwsSignal();
        s.setApplicationReference(exp.getApplicationReference());
        s.setCounterpartyRef(exp.getCounterpartyRef());
        s.setCounterpartyName(exp.getCounterpartyName());
        s.setSignalType(type);
        s.setSeverity(severity.name());
        s.setSource(source);
        s.setScore(Math.round(score * 1000.0) / 1000.0);
        s.setRationale(rationale);
        s.setProposedAction(proposedAction);
        return s;
    }

    private SignalSeverity mapSeverity(String breachSeverity) {
        return switch (breachSeverity == null ? "" : breachSeverity.toUpperCase()) {
            case "CRITICAL" -> SignalSeverity.SEVERE;
            case "MAJOR" -> SignalSeverity.HIGH;
            default -> SignalSeverity.MEDIUM;
        };
    }

    private boolean satisfies(double observed, String operator, double threshold) {
        return switch (operator) {
            case ">=" -> observed >= threshold;
            case ">" -> observed > threshold;
            case "<=" -> observed <= threshold;
            case "<" -> observed < threshold;
            case "==" -> Math.abs(observed - threshold) < 1e-9;
            default -> true;
        };
    }

    // --------------------------------------------------- advisory LLM narrative (fail-soft)

    /**
     * Advisory early-warning narrative grounded in the deterministic signal facts. Prose only — reuses the
     * supplied type / severity / score / threshold figures verbatim and never changes them; these remain
     * flags only, with staging and remediation human-decided. Returns {@code null} when not configured /
     * failed / empty so the caller keeps the deterministic rationale.
     */
    private LlmNarrative llmEwsNarrative(ExposureRecord exp, EwsSignal s) {
        String borrower = exp.getCounterpartyName() == null ? exp.getApplicationReference() : exp.getCounterpartyName();
        String system = "You are drafting an ADVISORY, non-binding early-warning signal narrative for a "
                + "wholesale-credit exposure. capability=ews-narrative. Write a short context summary of the signal "
                + "using ONLY the facts provided — the signal type, severity, source, score, the deterministic "
                + "rationale and the proposed action. Reuse every figure and threshold verbatim; never invent or "
                + "change any value. These are flags only: staging, reclassification and limit changes are "
                + "human-decided. Reply with 1-3 sentences of plain prose.";
        String user = "Borrower: " + borrower + "\nDeal: " + s.getApplicationReference()
                + "\nSignal type: " + s.getSignalType() + "\nSeverity: " + s.getSeverity()
                + "\nSource: " + s.getSource() + "\nScore (0-1): " + s.getScore()
                + "\nDeterministic rationale (source of truth): " + s.getRationale()
                + "\nProposed action: " + s.getProposedAction();
        LlmResult r = safeComplete(LlmRequest.of("ews-narrative", system, user));
        return r.usable() ? new LlmNarrative(r.text().strip(), r.model()) : null;
    }

    private LlmResult safeComplete(LlmRequest req) {
        try {
            LlmResult r = llm.complete(req);
            return r == null ? LlmResult.notConfigured() : r;
        } catch (Exception e) {
            return LlmResult.failed(e.getMessage());
        }
    }

    /** An advisory LLM-drafted narrative plus the model that produced it. */
    private record LlmNarrative(String text, String model) {
    }
}
