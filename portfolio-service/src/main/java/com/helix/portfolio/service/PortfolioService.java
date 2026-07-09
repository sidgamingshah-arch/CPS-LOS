package com.helix.portfolio.service;

import com.helix.common.audit.AuditService;
import com.helix.common.web.ApiException;
import com.helix.portfolio.client.PortfolioUpstreamClient;
import com.helix.portfolio.client.PortfolioUpstreamClient.CreditInputsDto;
import com.helix.portfolio.client.PortfolioUpstreamClient.RiskSummaryDto;
import com.helix.portfolio.client.PortfolioUpstreamClient.RulePackDto;
import com.helix.portfolio.dto.Dtos.ConcentrationLine;
import com.helix.portfolio.dto.Dtos.ConcentrationView;
import com.helix.portfolio.dto.Dtos.PortfolioSummary;
import com.helix.portfolio.dto.Dtos.StressOutcome;
import com.helix.portfolio.dto.Dtos.StressResult;
import com.helix.portfolio.dto.Dtos.StressScenario;
import com.helix.portfolio.entity.EclResult;
import com.helix.portfolio.entity.ExposureRecord;
import com.helix.portfolio.repo.EclResultRepository;
import com.helix.portfolio.repo.EwsSignalRepository;
import com.helix.portfolio.repo.ExposureRecordRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Book-level portfolio management (PRD §12): exposure registration, ECL/provisioning,
 * concentration vs limits, and stress testing. All computation is deterministic.
 */
@Service
public class PortfolioService {

    private final ExposureRecordRepository exposures;
    private final EclResultRepository eclResults;
    private final EwsSignalRepository signals;
    private final EclEngine eclEngine;
    private final EwsService ews;
    private final RarocTrackingService raroc;
    private final PortfolioUpstreamClient upstream;
    private final AuditService audit;

    public PortfolioService(ExposureRecordRepository exposures, EclResultRepository eclResults,
                            EwsSignalRepository signals, EclEngine eclEngine, EwsService ews,
                            RarocTrackingService raroc, PortfolioUpstreamClient upstream, AuditService audit) {
        this.exposures = exposures;
        this.eclResults = eclResults;
        this.signals = signals;
        this.eclEngine = eclEngine;
        this.ews = ews;
        this.raroc = raroc;
        this.upstream = upstream;
        this.audit = audit;
    }

    // --------------------------------------------------------------- exposures

    @Transactional
    public ExposureRecord register(String reference, int daysPastDue, String actor) {
        CreditInputsDto inputs = upstream.creditInputs(reference);
        RiskSummaryDto risk = upstream.riskSummary(reference);
        if (risk.rating() == null) {
            throw ApiException.conflict("Cannot book exposure: no rating exists for " + reference);
        }
        ExposureRecord e = exposures.findByApplicationReference(reference).orElseGet(ExposureRecord::new);
        e.setApplicationReference(reference);
        e.setCounterpartyRef(inputs.counterpartyRef());
        e.setCounterpartyName(inputs.counterpartyName());
        e.setJurisdiction(inputs.jurisdiction());
        e.setSegment(inputs.segment());
        // D6: book the real counterparty sector (from origination credit-inputs); fall back to the
        // segment only when a deal carries no sector, preserving legacy behaviour.
        e.setSector(inputs.sector() != null && !inputs.sector().isBlank() ? inputs.sector() : inputs.segment());
        e.setFacilityType(inputs.facilityType());
        e.setTenorMonths(inputs.tenorMonths());
        e.setGroupRef(upstream.groupRefFor(inputs.counterpartyRef()));
        e.setFinalGrade(risk.rating().finalGrade());
        // Origination-grade snapshot: set ONCE on the first booking, never overwritten on a
        // re-register (e is the loaded row on re-book, a fresh entity on first book).
        if (e.getOriginationGrade() == null) {
            e.setOriginationGrade(risk.rating().finalGrade());
        }
        // Collateral snapshot for the RBI doubtful secured/unsecured provisioning split.
        e.setCollateralValue(inputs.collateralValue());
        e.setSecured(inputs.secured());
        e.setPd(risk.rating().pd());
        e.setLgd(risk.rating().lgd());
        e.setEad(risk.rating().ead());
        e.setRwa(risk.capital() == null ? 0.0 : risk.capital().rwa());
        e.setCapitalRequired(risk.capital() == null ? 0.0 : risk.capital().capitalRequired());
        e.setCurrency(inputs.currency());
        e.setDaysPastDue(daysPastDue);
        ExposureRecord saved = exposures.save(e);
        audit.human(actor, "EXPOSURE_BOOKED", "Application", reference,
                "Booked %s exposure %.0f, grade %s".formatted(inputs.segment(), saved.getEad(), saved.getFinalGrade()),
                Map.of("ead", saved.getEad(), "grade", saved.getFinalGrade()));
        // Capture the projected-RAROC snapshot at the moment of booking — closes the loop
        // for projected-vs-actual tracking over the life of the facility (PRD §7).
        if (risk.pricing() != null) {
            raroc.snapshotOrigination(reference, actor);
        }
        return saved;
    }

    @Transactional(readOnly = true)
    public List<ExposureRecord> exposures() {
        return exposures.findAll();
    }

    @Transactional(readOnly = true)
    public ExposureRecord exposure(String reference) {
        return exposures.findByApplicationReference(reference)
                .orElseThrow(() -> ApiException.notFound("No booked exposure for " + reference));
    }

    // --------------------------------------------------------------------- ECL

    @Transactional
    public EclResult computeEcl(String reference, String actor) {
        ExposureRecord e = exposure(reference);
        RulePackDto pack = upstream.pack(e.getJurisdiction(), "PROVISIONING", fallbackProvisioning());
        PortfolioUpstreamClient.RestructureContextDto rc = upstream.restructureContext(reference);
        EclResult result = eclEngine.compute(e, pack,
                new EclEngine.RestructureContext(rc.restructured(), rc.restructuredAt()));
        EclResult saved = eclResults.save(result);
        audit.engine("ECL_COMPUTED", "Application", reference,
                "Stage %s, ECL %.0f, IRAC %.0f, reported %.0f (%s) per %s v%d".formatted(
                        saved.getStage(), saved.getEcl(), saved.getIracProvision(), saved.getReportedProvision(),
                        saved.getReportedProvisionPolicy(), pack.code(), pack.version()),
                Map.of("stage", saved.getStage(), "ecl", saved.getEcl(),
                        "reportedProvision", saved.getReportedProvision()));
        return saved;
    }

    @Transactional(readOnly = true)
    public EclResult latestEcl(String reference) {
        return eclResults.findFirstByApplicationReferenceOrderByCreatedAtDesc(reference)
                .orElseThrow(() -> ApiException.notFound("No ECL computed for " + reference));
    }

    @Transactional
    public PortfolioSummary summary() {
        List<ExposureRecord> all = exposures.findAll();
        double totalEad = 0;
        double totalRwa = 0;
        double totalProvision = 0;
        Map<String, Long> byStage = new TreeMap<>();
        Map<String, Double> provisionByStage = new TreeMap<>();

        for (ExposureRecord e : all) {
            totalEad += e.getEad();
            totalRwa += e.getRwa();
            RulePackDto pack = upstream.pack(e.getJurisdiction(), "PROVISIONING", fallbackProvisioning());
            EclResult ecl = eclEngine.compute(e, pack);
            totalProvision += ecl.getReportedProvision();
            byStage.merge(ecl.getStage(), 1L, Long::sum);
            provisionByStage.merge(ecl.getStage(), ecl.getReportedProvision(), Double::sum);
        }
        long openSignals = signals.findByStatusOrderByScoreDesc("OPEN").size();
        return new PortfolioSummary(all.size(), round(totalEad), round(totalRwa), round(totalProvision),
                byStage, provisionByStage, openSignals);
    }

    // ----------------------------------------------------------- concentration

    @Transactional(readOnly = true)
    public ConcentrationView concentration(String jurisdiction) {
        List<ExposureRecord> all = exposures.findAll();
        RulePackDto limits = upstream.pack(jurisdiction, "EXPOSURE_LIMITS", fallbackLimits());
        double capitalBase = limits.number("capital_base", 50_000_000_000d);
        double singleNamePct = limits.number("single_name_pct_capital", 0.15);
        double sectorPct = limits.number("sector_cap_pct_portfolio", 0.20);

        double total = all.stream().mapToDouble(ExposureRecord::getEad).sum();

        Map<String, Double> byName = aggregate(all, ExposureRecord::getCounterpartyName);
        Map<String, Double> bySector = aggregate(all, ExposureRecord::getSector);
        Map<String, Double> bySegment = aggregate(all, ExposureRecord::getSegment);

        List<String> breaches = new ArrayList<>();
        List<ConcentrationLine> nameLines = limitLines(byName, total, capitalBase, singleNamePct, true, breaches, "Single-name");
        List<ConcentrationLine> sectorLines = limitLines(bySector, total, total, sectorPct, false, breaches, "Sector");
        List<ConcentrationLine> segmentLines = limitLines(bySegment, total, total, sectorPct, false, breaches, "Segment");

        return new ConcentrationView(jurisdiction, round(total), capitalBase,
                nameLines, sectorLines, segmentLines, breaches);
    }

    private List<ConcentrationLine> limitLines(Map<String, Double> agg, double total, double base,
                                               double limitPct, boolean vsCapital, List<String> breaches, String dim) {
        double limitAmount = base * limitPct;
        List<ConcentrationLine> lines = new ArrayList<>();
        agg.forEach((key, exp) -> {
            double share = total > 0 ? exp / total : 0;
            double utilisation = limitAmount > 0 ? exp / limitAmount : 0;
            boolean breach = exp > limitAmount;
            if (breach) {
                breaches.add("%s limit breached: %s at %.0f vs limit %.0f".formatted(dim, key, exp, limitAmount));
            }
            String band = utilisation > 1.0 + 1e-6 ? "BREACH"
                    : utilisation >= 0.90 ? "WARNING"
                    : utilisation >= 0.80 ? "WATCH" : "NORMAL";
            lines.add(new ConcentrationLine(key, key, round(exp), round4(share), limitPct,
                    round(limitAmount), round4(utilisation), breach, band));
        });
        lines.sort((a, b) -> Double.compare(b.exposure(), a.exposure()));
        return lines;
    }

    private Map<String, Double> aggregate(List<ExposureRecord> all, java.util.function.Function<ExposureRecord, String> key) {
        Map<String, Double> m = new LinkedHashMap<>();
        for (ExposureRecord e : all) {
            m.merge(key.apply(e) == null ? "UNKNOWN" : key.apply(e), e.getEad(), Double::sum);
        }
        return m;
    }

    // ----------------------------------------------------------------- stress

    @Transactional(readOnly = true)
    public StressResult stress() {
        List<ExposureRecord> all = exposures.findAll();
        List<StressScenario> scenarios = List.of(
                new StressScenario("BASELINE", 1.0, 0.0),
                new StressScenario("ADVERSE", 1.8, 0.05),
                new StressScenario("SEVERE", 3.0, 0.12));

        double baselineEcl = all.stream().mapToDouble(e -> e.getPd() * e.getLgd() * e.getEad()).sum();
        List<StressOutcome> outcomes = new ArrayList<>();
        for (StressScenario s : scenarios) {
            double stressedEcl = 0;
            double stressedRwa = 0;
            for (ExposureRecord e : all) {
                double pd = Math.min(1.0, e.getPd() * s.pdMultiplier());
                double lgd = Math.min(1.0, e.getLgd() + s.lgdAddOn());
                stressedEcl += pd * lgd * e.getEad();
                // crude RWA sensitivity: scale with PD multiplier, capped.
                stressedRwa += e.getRwa() * Math.min(2.5, Math.sqrt(s.pdMultiplier()));
            }
            outcomes.add(new StressOutcome(s.name(), round(baselineEcl), round(stressedEcl),
                    round(stressedEcl - baselineEcl), round(stressedRwa)));
        }
        return new StressResult(outcomes);
    }

    // --------------------------------------------------------------- EWS proxy

    @Transactional
    public List<com.helix.portfolio.entity.EwsSignal> scan(String reference, String actor) {
        return ews.scan(exposure(reference));
    }

    @Transactional
    public List<com.helix.portfolio.entity.EwsSignal> scanAll(String actor) {
        List<com.helix.portfolio.entity.EwsSignal> all = new ArrayList<>();
        for (ExposureRecord e : exposures.findAll()) {
            all.addAll(ews.scan(e));
        }
        return all;
    }

    // ---- monitoring loop closure: EWS scan -> auto-escalate severe signals to collections ----

    /** One exposure's monitoring outcome. */
    public record SweepResult(String applicationReference, String counterpartyName,
                              int signalsRaised, boolean escalated, List<String> escalationSignals,
                              Long collectionsCaseId) {
    }

    /**
     * Closes the monitoring loop for one deal: run the EWS scan, then escalate qualifying
     * signals into a (SYSTEM, idempotent) collections case on decision-service. The
     * escalation bar is a SEVERE signal, a covenant breach, or 90+ DPD — the points at
     * which a deal stops being a watchlist item and becomes a workout. The case is a
     * shell; the actual workout (restructure / legal / write-off) stays human + DoA gated.
     */
    @Transactional
    public SweepResult monitorSweep(String reference, String actor) {
        ExposureRecord exp = exposure(reference);
        List<com.helix.portfolio.entity.EwsSignal> raised = ews.scan(exp);
        List<String> escalating = new ArrayList<>();
        for (com.helix.portfolio.entity.EwsSignal s : raised) {
            boolean severe = "SEVERE".equalsIgnoreCase(s.getSeverity());
            boolean covenant = "COVENANT_BREACH".equals(s.getSignalType());
            boolean badDpd = "DAYS_PAST_DUE".equals(s.getSignalType()) && exp.getDaysPastDue() >= 90;
            if (severe || covenant || badDpd) {
                escalating.add(s.getSignalType());
            }
        }
        Long caseId = null;
        if (!escalating.isEmpty()) {
            String trigger = "EWS: " + String.join(", ", escalating);
            // Covenant-only signals carry no overdue; DPD signals pass the days so the
            // case stages itself. The human enters the real overdue when working it.
            caseId = upstream.openCollectionsCase(reference, exp.getDaysPastDue(), 0.0, trigger);
        }
        audit.engine("MONITORING_SWEEP", "Application", reference,
                "Swept %s: %d signal(s)%s".formatted(reference, raised.size(),
                        escalating.isEmpty() ? "" : " — escalated to collections (" + String.join(", ", escalating) + ")"),
                Map.of("signals", raised.size(), "escalated", !escalating.isEmpty(),
                        "caseId", caseId == null ? "" : caseId));
        return new SweepResult(reference, exp.getCounterpartyName(), raised.size(),
                !escalating.isEmpty(), escalating, caseId);
    }

    @Transactional
    public List<SweepResult> monitorSweepAll(String actor) {
        List<SweepResult> out = new ArrayList<>();
        for (ExposureRecord e : exposures.findAll()) {
            out.add(monitorSweep(e.getApplicationReference(), actor));
        }
        return out;
    }

    // --------------------------------------------------------------- fallbacks

    private RulePackDto fallbackProvisioning() {
        return new RulePackDto("fallback_provisioning", 0, Map.of(
                "sicr_dpd_stage2", 30, "sicr_dpd_stage3", 90, "ecl_macro_overlay", 1.10,
                "irac_provision_rates", Map.of("STANDARD", 0.004, "SUB_STANDARD", 0.15, "DOUBTFUL", 0.40, "LOSS", 1.0),
                "reported_provision_policy", "max(ecl,irac)"));
    }

    private RulePackDto fallbackLimits() {
        return new RulePackDto("fallback_limits", 0, Map.of(
                "single_name_pct_capital", 0.15, "connected_group_pct_capital", 0.25,
                "sector_cap_pct_portfolio", 0.20, "geography_cap_pct_portfolio", 0.30,
                "capital_base", 50_000_000_000d));
    }

    private double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    private double round4(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }
}
