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
        e.setSector(inputs.segment());   // sector proxy; counterparty sector would refine this
        e.setFinalGrade(risk.rating().finalGrade());
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
        EclResult result = eclEngine.compute(e, pack);
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
            lines.add(new ConcentrationLine(key, key, round(exp), round4(share), limitPct,
                    round(limitAmount), round4(utilisation), breach));
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
