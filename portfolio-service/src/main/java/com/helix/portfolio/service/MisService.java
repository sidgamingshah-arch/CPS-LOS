package com.helix.portfolio.service;

import com.helix.portfolio.entity.EwsSignal;
import com.helix.portfolio.entity.ExposureRecord;
import com.helix.portfolio.entity.RarocTracking;
import com.helix.portfolio.repo.EwsSignalRepository;
import com.helix.portfolio.repo.ExposureRecordRepository;
import com.helix.portfolio.repo.RarocTrackingRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Management-information consolidation across the book (PRD §12/§13). Pure
 * read-only aggregations — no AI in the path; figures are quoted from the
 * services that own them.
 */
@Service
public class MisService {

    private final ExposureRecordRepository exposures;
    private final RarocTrackingRepository raroc;
    private final EwsSignalRepository signals;
    private final EclEngine eclEngine;

    public MisService(ExposureRecordRepository exposures, RarocTrackingRepository raroc,
                      EwsSignalRepository signals, EclEngine eclEngine) {
        this.exposures = exposures;
        this.raroc = raroc;
        this.signals = signals;
        this.eclEngine = eclEngine;
    }

    /** Book composition by segment / grade / jurisdiction — drives portfolio dashboards. */
    @Transactional(readOnly = true)
    public Map<String, Object> bookComposition() {
        List<ExposureRecord> all = exposures.findAll();
        Map<String, Double> bySegment = new TreeMap<>();
        Map<String, Double> byGrade = new TreeMap<>();
        Map<String, Double> byJurisdiction = new TreeMap<>();
        Map<String, Double> byStatus = new TreeMap<>();
        for (ExposureRecord e : all) {
            bySegment.merge(nullSafe(e.getSegment()), e.getEad(), Double::sum);
            byGrade.merge(nullSafe(e.getFinalGrade()), e.getEad(), Double::sum);
            byJurisdiction.merge(nullSafe(e.getJurisdiction()), e.getEad(), Double::sum);
            byStatus.merge(nullSafe(e.getStatus()), e.getEad(), Double::sum);
        }
        return Map.of(
                "exposureCount", all.size(),
                "totalEad", round(all.stream().mapToDouble(ExposureRecord::getEad).sum()),
                "bySegment", bySegment,
                "byGrade", byGrade,
                "byJurisdiction", byJurisdiction,
                "byStatus", byStatus);
    }

    /** Variance of actual vs projected RAROC across the book. */
    @Transactional(readOnly = true)
    public Map<String, Object> rarocVariance() {
        List<RarocTracking> actuals = raroc.findAll().stream().filter(t -> !t.isOrigination()).toList();
        if (actuals.isEmpty()) {
            return Map.of("trackedDeals", 0, "note", "No actual-RAROC computations yet.");
        }
        double avgVar = actuals.stream().mapToDouble(RarocTracking::getVariance).average().orElse(0.0);
        double avgAbsPct = actuals.stream().mapToDouble(RarocTracking::getAbsVariancePct).average().orElse(0.0);
        long materialMiss = actuals.stream().filter(t -> t.getAbsVariancePct() > 0.25).count();
        long below = actuals.stream().filter(t -> t.getVariance() < 0).count();
        List<Map<String, Object>> worst = actuals.stream()
                .sorted((a, b) -> Double.compare(b.getAbsVariancePct(), a.getAbsVariancePct()))
                .limit(10)
                .map(t -> Map.<String, Object>of(
                        "reference", t.getApplicationReference(),
                        "period", t.getPeriodLabel(),
                        "projectedRaroc", t.getProjectedRaroc(),
                        "actualRaroc", t.getActualRaroc(),
                        "variance", t.getVariance(),
                        "absVariancePct", t.getAbsVariancePct()))
                .toList();
        return Map.of(
                "trackedDeals", actuals.size(),
                "averageVariance", round4(avgVar),
                "averageAbsVariancePct", round4(avgAbsPct),
                "belowProjected", below,
                "materialMisses", materialMiss,
                "materialAlertThreshold", 0.25,
                "worstByVariance", worst);
    }

    /** Pipeline ageing — how long exposures have been on the book by status. */
    @Transactional(readOnly = true)
    public Map<String, Object> pipelineAgeing() {
        LocalDate today = LocalDate.now();
        Map<String, Long> ageByStatus = new TreeMap<>();
        Map<String, Long> countByStatus = new TreeMap<>();
        for (ExposureRecord e : exposures.findAll()) {
            String s = nullSafe(e.getStatus());
            long days = ChronoUnit.DAYS.between(e.getCreatedAt().atZone(java.time.ZoneOffset.UTC).toLocalDate(), today);
            ageByStatus.merge(s, days, Long::sum);
            countByStatus.merge(s, 1L, Long::sum);
        }
        Map<String, Double> avgAge = new LinkedHashMap<>();
        ageByStatus.forEach((s, total) -> avgAge.put(s, (double) total / countByStatus.get(s)));
        return Map.of("avgAgeDays", avgAge, "countByStatus", countByStatus);
    }

    /** ECL split by stage × jurisdiction — the regulatory-return-shaped view. */
    @Transactional(readOnly = true)
    public Map<String, Object> eclByStage() {
        Map<String, Map<String, Double>> byJurStage = new TreeMap<>();
        for (ExposureRecord e : exposures.findAll()) {
            // Use a fast ECL recompute via the engine with a fallback pack so we don't
            // require a fresh ECL row. This MIS view is approximate by design.
            var pack = new com.helix.portfolio.client.PortfolioUpstreamClient.RulePackDto(
                    "mis_fallback", 0, Map.of("sicr_dpd_stage2", 30, "sicr_dpd_stage3", 90,
                    "ecl_macro_overlay", 1.10, "irac_provision_rates", Map.of(
                            "STANDARD", 0.004, "SUB_STANDARD", 0.15, "DOUBTFUL", 0.40, "LOSS", 1.0),
                    "reported_provision_policy", "max(ecl,irac)"));
            var r = eclEngine.compute(e, pack);
            byJurStage.computeIfAbsent(nullSafe(e.getJurisdiction()), k -> new TreeMap<>())
                    .merge(r.getStage(), r.getReportedProvision(), Double::sum);
        }
        return Map.of("byJurisdictionStage", byJurStage);
    }

    /** Top EWS signals across the open watchlist — drives the watch dashboard. */
    @Transactional(readOnly = true)
    public Map<String, Object> watchlistSummary() {
        List<EwsSignal> open = signals.findByStatusOrderByScoreDesc("OPEN");
        Map<String, Long> bySeverity = new TreeMap<>();
        Map<String, Long> bySignal = new TreeMap<>();
        for (EwsSignal s : open) {
            bySeverity.merge(s.getSeverity(), 1L, Long::sum);
            bySignal.merge(s.getSignalType(), 1L, Long::sum);
        }
        return Map.of("openCount", open.size(), "bySeverity", bySeverity, "bySignalType", bySignal);
    }

    /** One-shot MIS dashboard payload — all of the above in a single call. */
    @Transactional(readOnly = true)
    public Map<String, Object> dashboard() {
        Map<String, Object> all = new LinkedHashMap<>();
        all.put("composition", bookComposition());
        all.put("rarocVariance", rarocVariance());
        all.put("pipelineAgeing", pipelineAgeing());
        all.put("watchlist", watchlistSummary());
        return all;
    }

    private String nullSafe(String s) {
        return s == null ? "UNKNOWN" : s;
    }

    private double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    private double round4(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }
}
