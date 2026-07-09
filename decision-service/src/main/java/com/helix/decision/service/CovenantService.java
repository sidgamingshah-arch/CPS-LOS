package com.helix.decision.service;

import com.helix.common.audit.AuditService;
import com.helix.common.model.Enums.BreachSeverity;
import com.helix.common.web.ApiException;
import com.helix.decision.client.UpstreamClient;
import com.helix.decision.client.UpstreamClient.CreditInputsDto;
import com.helix.decision.dto.Dtos.AddCovenantRequest;
import com.helix.decision.entity.Covenant;
import com.helix.decision.entity.CovenantTest;
import com.helix.decision.repo.CovenantRepository;
import com.helix.decision.repo.CovenantTestRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Structured covenant management (PRD §7). Covenants are tested in monitoring (§11). */
@Service
public class CovenantService {

    private final CovenantRepository covenants;
    private final CovenantTestRepository tests;
    private final UpstreamClient upstream;
    private final AuditService audit;

    public CovenantService(CovenantRepository covenants, CovenantTestRepository tests,
                           UpstreamClient upstream, AuditService audit) {
        this.covenants = covenants;
        this.tests = tests;
        this.upstream = upstream;
        this.audit = audit;
    }

    @Transactional
    public Covenant add(String reference, AddCovenantRequest req, String actor) {
        Covenant c = new Covenant();
        c.setApplicationReference(reference);
        c.setCovenantType(req.covenantType());
        c.setMetric(req.metric());
        c.setOperator(req.operator());
        c.setThreshold(req.threshold());
        c.setTestFrequency(req.testFrequency());
        c.setSource(req.source());
        c.setCurePeriodDays(req.curePeriodDays());
        c.setBreachSeverity(BreachSeverity.valueOf(req.breachSeverity().toUpperCase()).name());
        c.setOnBreach(req.onBreach());
        c.setCreatedBy(actor);
        Covenant saved = covenants.save(c);
        audit.human(actor, "COVENANT_ADDED", "Application", reference,
                "%s %s %.2f tested %s".formatted(req.metric(), req.operator(), req.threshold(), req.testFrequency()),
                Map.of("metric", req.metric(), "operator", req.operator(), "threshold", req.threshold()));
        return saved;
    }

    @Transactional(readOnly = true)
    public List<Covenant> list(String reference) {
        return covenants.findByApplicationReference(reference);
    }

    @Transactional
    public void deactivate(Long id, String actor) {
        Covenant c = covenants.findById(id).orElseThrow(() -> ApiException.notFound("No covenant: " + id));
        c.setActive(false);
        covenants.save(c);
    }

    /**
     * Tests every active covenant against the latest spread ratios and records the
     * observation history. Breaches are written through the audit trail; the EWS
     * agent (portfolio-service) consumes covenant breaches as a flag — humans decide
     * staging and remediation, never the agent (PRD §11).
     */
    @Transactional
    public List<CovenantTest> testAll(String reference, String actor) {
        CreditInputsDto inputs = upstream.creditInputs(reference);
        Map<String, Double> ratios = inputs.ratios() == null ? Map.of() : inputs.ratios();
        List<Covenant> active = covenants.findByApplicationReference(reference).stream()
                .filter(Covenant::isActive).toList();

        List<CovenantTest> results = new ArrayList<>();
        int breached = 0;
        for (Covenant c : active) {
            double observed = ratios.getOrDefault(c.getMetric(), 0.0);
            boolean passed = satisfies(observed, c.getOperator(), c.getThreshold());
            if (!passed) breached++;
            CovenantTest t = new CovenantTest();
            t.setCovenantId(c.getId());
            t.setApplicationReference(reference);
            t.setMetric(c.getMetric());
            t.setOperator(c.getOperator());
            t.setThreshold(c.getThreshold());
            t.setObserved(observed);
            t.setPassed(passed);
            t.setSource(c.getSource() == null ? "borrower_management_accounts" : c.getSource());
            t.setBreachSeverity(c.getBreachSeverity());
            results.add(tests.save(t));
        }
        audit.engine("COVENANTS_TESTED", "Application", reference,
                "Tested %d covenant(s); %d breached".formatted(active.size(), breached),
                Map.of("tested", active.size(), "breached", breached));
        return results;
    }

    @Transactional(readOnly = true)
    public List<CovenantTest> testHistory(String reference) {
        return tests.findByApplicationReferenceOrderByTestedAtDesc(reference);
    }

    private boolean satisfies(double observed, String op, double threshold) {
        return switch (op) {
            case ">=" -> observed >= threshold;
            case ">" -> observed > threshold;
            case "<=" -> observed <= threshold;
            case "<" -> observed < threshold;
            case "==" -> Math.abs(observed - threshold) < 1e-9;
            default -> true;
        };
    }

    /** AI [C] covenant suggestions tied to the risk profile (PRD §7, US-7.2). Analyst edits/accepts. */
    public List<AddCovenantRequest> suggest(String finalGrade) {
        boolean weak = List.of("BB", "B", "CCC", "CC", "C", "D").contains(finalGrade);
        // Base catalogue from the COVENANT_LIBRARY master; weak grades tighten the financials.
        // A config outage degrades to the built-in fallback so suggestions never go empty.
        var catalogue = upstream.masters("COVENANT_LIBRARY");
        if (catalogue.isEmpty()) {
            return hardcodedFallback(weak);
        }
        List<AddCovenantRequest> out = new ArrayList<>();
        for (var r : catalogue) {
            Map<String, Object> p = r.payload();
            String op = String.valueOf(p.getOrDefault("operator", ""));
            if (!List.of(">=", ">", "<=", "<", "==").contains(op)) {
                continue;   // skip BY_DATE / NEGATIVE-pledge style library entries — not testable thresholds
            }
            if (!(p.get("defaultThreshold") instanceof Number base)) {
                continue;   // skip threshold-less entries
            }
            boolean fin = "FINANCIAL".equalsIgnoreCase(String.valueOf(p.get("category")));
            out.add(new AddCovenantRequest(
                    fin ? "FINANCIAL_MAINTENANCE" : "INFORMATION",
                    r.recordKey(), op, tighten(r.recordKey(), base.doubleValue(), weak),
                    fin ? "QUARTERLY" : "ANNUAL",
                    fin ? "borrower_management_accounts" : "audited_financials",
                    fin ? 30 : 0, fin ? "MAJOR" : "MINOR",
                    fin ? List.of("notify_RM", "raise_EWS") : List.of("notify_RM")));
        }
        return out.isEmpty() ? hardcodedFallback(weak) : out;
    }

    /** Weak grades tighten the two financial covenants; the rest ride the library's default threshold. */
    private double tighten(String metric, double base, boolean weak) {
        if (!weak) {
            return base;
        }
        return switch (metric) {
            case "DSCR" -> Math.max(base, 1.35);
            case "NET_LEVERAGE" -> Math.min(base, 3.0);
            default -> base;
        };
    }

    /** Built-in suggestions used only when the COVENANT_LIBRARY master is unreachable. */
    private List<AddCovenantRequest> hardcodedFallback(boolean weak) {
        double dscr = weak ? 1.35 : 1.25;
        double leverage = weak ? 3.0 : 3.5;
        return List.of(
                new AddCovenantRequest("FINANCIAL_MAINTENANCE", "DSCR", ">=", dscr, "QUARTERLY",
                        "borrower_management_accounts", 30, "MAJOR",
                        List.of("notify_RM", "raise_EWS", "trigger_review")),
                new AddCovenantRequest("FINANCIAL_MAINTENANCE", "NET_LEVERAGE", "<=", leverage, "QUARTERLY",
                        "borrower_management_accounts", 30, "MAJOR",
                        List.of("notify_RM", "raise_EWS")),
                new AddCovenantRequest("INFORMATION", "INTEREST_COVERAGE", ">=", 2.0, "ANNUAL",
                        "audited_financials", 0, "MINOR", List.of("notify_RM")));
    }
}
