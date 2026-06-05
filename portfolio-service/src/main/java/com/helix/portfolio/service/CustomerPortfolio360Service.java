package com.helix.portfolio.service;

import com.helix.common.web.ApiException;
import com.helix.portfolio.client.PortfolioUpstreamClient;
import com.helix.portfolio.entity.EwsSignal;
import com.helix.portfolio.entity.ExposureRecord;
import com.helix.portfolio.entity.RarocTracking;
import com.helix.portfolio.repo.EclResultRepository;
import com.helix.portfolio.repo.EwsSignalRepository;
import com.helix.portfolio.repo.ExposureRecordRepository;
import com.helix.portfolio.repo.RarocTrackingRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Customer-360 (per-borrower) and Portfolio-360 (book-level) aggregations.
 * Data already lives across the services; here we compose a single payload for
 * the dashboards (PRD Customer-360 / Portfolio-360 widgets).
 */
@Service
public class CustomerPortfolio360Service {

    private final ExposureRecordRepository exposures;
    private final EclResultRepository ecl;
    private final EwsSignalRepository signals;
    private final RarocTrackingRepository raroc;
    private final PortfolioUpstreamClient upstream;

    public CustomerPortfolio360Service(ExposureRecordRepository exposures, EclResultRepository ecl,
                                       EwsSignalRepository signals, RarocTrackingRepository raroc,
                                       PortfolioUpstreamClient upstream) {
        this.exposures = exposures;
        this.ecl = ecl;
        this.signals = signals;
        this.raroc = raroc;
        this.upstream = upstream;
    }

    // --------------------------------------------------------------- Customer-360

    @Transactional(readOnly = true)
    public Map<String, Object> customer360(String reference) {
        ExposureRecord exp = exposures.findByApplicationReference(reference)
                .orElseThrow(() -> ApiException.notFound("No booked exposure for " + reference));

        Map<String, Object> profile = new LinkedHashMap<>();
        profile.put("counterpartyRef", exp.getCounterpartyRef());
        profile.put("counterpartyName", exp.getCounterpartyName());
        profile.put("segment", exp.getSegment());
        profile.put("jurisdiction", exp.getJurisdiction());
        profile.put("internalRating", exp.getFinalGrade());

        Map<String, Object> limits = Map.of(
                "ead", exp.getEad(),
                "rwa", exp.getRwa(),
                "capitalRequired", exp.getCapitalRequired(),
                "daysPastDue", exp.getDaysPastDue(),
                "status", exp.getStatus());

        // Triggers / EWS
        List<EwsSignal> sigs = signals.findByApplicationReferenceOrderByScoreDesc(reference);
        Map<String, Long> sigsBySeverity = new TreeMap<>();
        sigs.forEach(s -> sigsBySeverity.merge(s.getSeverity(), 1L, Long::sum));

        // Financials & ratios — sourced from origination (single read).
        Map<String, Object> creditInputs = upstream.getMap("origination", "/api/applications/{r}/credit-inputs", reference);
        Map<String, Object> financials = creditInputs.get("latestFinancials") instanceof Map<?, ?> m1
                ? (Map<String, Object>) m1 : Map.of();
        Map<String, Object> ratios = creditInputs.get("ratios") instanceof Map<?, ?> m2
                ? (Map<String, Object>) m2 : Map.of();

        // External outlook (industry benchmark from masters; we use config endpoint).
        Map<String, Object> industry = Map.of();
        List<Map<String, Object>> bench = upstream.getList("config", "/api/masters/INDUSTRY_BENCHMARK");
        for (Map<String, Object> r : bench) {
            if (exp.getSegment() != null && exp.getSegment().equalsIgnoreCase(String.valueOf(r.get("recordKey")))) {
                industry = r.get("payload") instanceof Map<?, ?> p ? (Map<String, Object>) p : Map.of();
                break;
            }
        }

        // Covenants
        Map<String, Object> covenants = Map.of(
                "count", upstream.getList("decision", "/api/decisions/{r}/covenants", reference).size(),
                "tests", upstream.getList("decision", "/api/decisions/{r}/covenants/tests", reference).size());

        // RAROC tracking
        List<RarocTracking> rrs = raroc.findByApplicationReferenceOrderByComputedAtAsc(reference);
        Map<String, Object> raroCSummary = rrs.isEmpty() ? Map.of("tracked", false) :
                Map.of("tracked", true,
                        "projected", rrs.get(0).getProjectedRaroc(),
                        "latestActual", rrs.get(rrs.size() - 1).getActualRaroc(),
                        "latestVariance", rrs.get(rrs.size() - 1).getVariance(),
                        "materialMiss", rrs.get(rrs.size() - 1).getAbsVariancePct() > 0.25);

        // Provisioning
        Map<String, Object> ecll = ecl.findFirstByApplicationReferenceOrderByCreatedAtDesc(reference)
                .map(e -> Map.<String, Object>of("stage", e.getStage(),
                        "reportedProvision", e.getReportedProvision(),
                        "policy", e.getReportedProvisionPolicy()))
                .orElse(Map.of("stage", "—"));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("reference", reference);
        out.put("borrowerProfile", profile);
        out.put("limitsAndUtilisation", limits);
        out.put("triggersAndBreaches", Map.of("openSignals", sigs.stream().filter(s -> "OPEN".equals(s.getStatus())).count(),
                "bySeverity", sigsBySeverity, "recent", sigs.stream().limit(5).toList()));
        out.put("financials", financials);
        out.put("ratios", ratios);
        out.put("covenants", covenants);
        out.put("raroc", raroCSummary);
        out.put("provisioning", ecll);
        out.put("externalOutlook", Map.of("industryBenchmark", industry));
        return out;
    }

    // --------------------------------------------------------------- Portfolio-360

    @Transactional(readOnly = true)
    public Map<String, Object> portfolio360(String filterRm) {
        List<ExposureRecord> all = exposures.findAll();
        if (filterRm != null && !filterRm.isBlank()) {
            // RM tagging lives in counterparty-service; for now this is a passthrough seam.
        }
        Map<String, Double> bySegment = new TreeMap<>();
        Map<String, Double> byGrade = new TreeMap<>();
        Map<String, Double> byJurisdiction = new TreeMap<>();
        Map<String, Long> byStatus = new TreeMap<>();
        Map<String, Double> byVintageYear = new TreeMap<>();
        for (ExposureRecord e : all) {
            bySegment.merge(safe(e.getSegment()), e.getEad(), Double::sum);
            byGrade.merge(safe(e.getFinalGrade()), e.getEad(), Double::sum);
            byJurisdiction.merge(safe(e.getJurisdiction()), e.getEad(), Double::sum);
            byStatus.merge(safe(e.getStatus()), 1L, Long::sum);
            if (e.getCreatedAt() != null) {
                String year = String.valueOf(e.getCreatedAt().atZone(java.time.ZoneOffset.UTC).getYear());
                byVintageYear.merge(year, e.getEad(), Double::sum);
            }
        }
        double totalEad = all.stream().mapToDouble(ExposureRecord::getEad).sum();
        double totalRwa = all.stream().mapToDouble(ExposureRecord::getRwa).sum();
        long openSigs = signals.findByStatusOrderByScoreDesc("OPEN").size();

        return Map.of(
                "exposureCount", all.size(),
                "totalEad", round(totalEad),
                "totalRwa", round(totalRwa),
                "openSignals", openSigs,
                "byInternalRating", byGrade,
                "bySegment", bySegment,
                "byJurisdiction", byJurisdiction,
                "byStatus", byStatus,
                "byVintageYear", byVintageYear);
    }

    private String safe(String s) {
        return s == null ? "UNKNOWN" : s;
    }

    private double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
