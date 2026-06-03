package com.helix.portfolio.service;

import com.helix.common.audit.AuditService;
import com.helix.common.web.ApiException;
import com.helix.portfolio.client.PortfolioUpstreamClient;
import com.helix.portfolio.client.PortfolioUpstreamClient.PricingDto;
import com.helix.portfolio.client.PortfolioUpstreamClient.RiskSummaryDto;
import com.helix.portfolio.entity.ExposureRecord;
import com.helix.portfolio.entity.RarocTracking;
import com.helix.portfolio.repo.ExposureRecordRepository;
import com.helix.portfolio.repo.RarocTrackingRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Projected-vs-actual RAROC tracking (PRD §7 / §12). The projected RAROC is
 * snapshotted at origination from the pricing engine; the actual is computed
 * periodically from realised conduct (interest income, EL realised via stage
 * migration / write-offs, funding, opex) divided by the capital projection.
 * Variance feeds the model-fit signals alongside override-rate (§11 governance).
 */
@Service
public class RarocTrackingService {

    private static final double DEFAULT_HOLDING_YEARS = 0.25;     // quarterly accrual
    private static final double OPEX_RATE = 0.010;

    private final RarocTrackingRepository tracking;
    private final ExposureRecordRepository exposures;
    private final PortfolioUpstreamClient upstream;
    private final AuditService audit;

    public RarocTrackingService(RarocTrackingRepository tracking, ExposureRecordRepository exposures,
                                PortfolioUpstreamClient upstream, AuditService audit) {
        this.tracking = tracking;
        this.exposures = exposures;
        this.upstream = upstream;
        this.audit = audit;
    }

    /** Captures the projected-RAROC snapshot at origination. Idempotent per reference. */
    @Transactional
    public RarocTracking snapshotOrigination(String reference, String actor) {
        var prior = tracking.findFirstByApplicationReferenceAndOriginationTrueOrderByComputedAtAsc(reference);
        if (prior.isPresent()) {
            return prior.get();
        }
        RiskSummaryDto rs = upstream.riskSummary(reference);
        if (rs == null || rs.pricing() == null) {
            throw ApiException.conflict("No pricing exists for " + reference + " — cannot snapshot projected RAROC");
        }
        PricingDto p = rs.pricing();
        RarocTracking t = new RarocTracking();
        t.setApplicationReference(reference);
        t.setPeriodLabel("ORIGINATION");
        t.setOrigination(true);
        t.setProjectedRaroc(p.raroc());
        t.setProjectedRecommendedRate(p.recommendedRate());
        t.setProjectedExpectedLoss(p.expectedLoss());
        t.setProjectedCapitalCharge(p.capitalCharge());
        t.setActualRaroc(p.raroc());
        t.setVariance(0.0);
        t.setAbsVariancePct(0.0);
        t.setDrivers(Map.of("note", "Projected RAROC snapshot at origination",
                "ead", p.ead(), "hurdle", p.hurdleRaroc()));
        RarocTracking saved = tracking.save(t);
        audit.engine("RAROC_SNAPSHOT_ORIGINATION", "Application", reference,
                "Captured projected RAROC %.2f%% at origination".formatted(p.raroc() * 100),
                Map.of("projectedRaroc", p.raroc(), "recommendedRate", p.recommendedRate()));
        return saved;
    }

    /**
     * Recomputes actual RAROC for the current period from realised conduct.
     * Realised income approximates accrued interest at the recommended rate over the
     * elapsed holding period (proxy for what would be sourced from GL income). EL
     * realised is the change in IRAC/stage provision since origination. Funding and
     * opex are flat rates.
     */
    @Transactional
    public RarocTracking computeActual(String reference, String periodLabel, double realisedProvisionDelta, String actor) {
        ExposureRecord exp = exposures.findByApplicationReference(reference)
                .orElseThrow(() -> ApiException.notFound("No booked exposure for " + reference));
        RiskSummaryDto rs = upstream.riskSummary(reference);
        if (rs == null || rs.pricing() == null) {
            throw ApiException.conflict("No pricing exists for " + reference);
        }
        PricingDto p = rs.pricing();
        RarocTracking origination = tracking.findFirstByApplicationReferenceAndOriginationTrueOrderByComputedAtAsc(reference)
                .orElseGet(() -> snapshotOrigination(reference, actor));

        double income = exp.getEad() * p.recommendedRate() * DEFAULT_HOLDING_YEARS;
        double cof = exp.getEad() * 0.075 * DEFAULT_HOLDING_YEARS;
        double opex = exp.getEad() * OPEX_RATE * DEFAULT_HOLDING_YEARS;
        double el = realisedProvisionDelta + p.expectedLoss() * DEFAULT_HOLDING_YEARS;
        double capital = p.capitalCharge();
        double actualRaroc = capital > 0 ? (income - el - opex - cof) / capital : 0.0;
        double variance = actualRaroc - origination.getProjectedRaroc();
        double absPct = origination.getProjectedRaroc() == 0 ? Math.abs(actualRaroc)
                : Math.abs(variance) / Math.abs(origination.getProjectedRaroc());

        RarocTracking t = new RarocTracking();
        t.setApplicationReference(reference);
        t.setPeriodLabel(periodLabel == null || periodLabel.isBlank() ? LocalDate.now().toString() : periodLabel);
        t.setOrigination(false);
        t.setProjectedRaroc(origination.getProjectedRaroc());
        t.setProjectedRecommendedRate(origination.getProjectedRecommendedRate());
        t.setProjectedExpectedLoss(origination.getProjectedExpectedLoss());
        t.setProjectedCapitalCharge(origination.getProjectedCapitalCharge());
        t.setActualRaroc(round4(actualRaroc));
        t.setActualIncome(round(income));
        t.setActualExpectedLossRealised(round(el));
        t.setActualCostOfFunds(round(cof));
        t.setActualOpex(round(opex));
        t.setVariance(round4(variance));
        t.setAbsVariancePct(round4(absPct));

        Map<String, Object> drivers = new LinkedHashMap<>();
        drivers.put("ead", exp.getEad());
        drivers.put("recommendedRate", p.recommendedRate());
        drivers.put("holdingYears", DEFAULT_HOLDING_YEARS);
        drivers.put("realisedProvisionDelta", realisedProvisionDelta);
        drivers.put("incomeIncluded", round(income));
        drivers.put("elRealised", round(el));
        drivers.put("costOfFunds", round(cof));
        drivers.put("opex", round(opex));
        drivers.put("capital", round(capital));
        drivers.put("note", "Actual RAROC = (income - EL - opex - CoF) / capital");
        t.setDrivers(drivers);

        RarocTracking saved = tracking.save(t);
        audit.engine("RAROC_ACTUAL_COMPUTED", "Application", reference,
                "Actual RAROC %.2f%% (vs projected %.2f%%, variance %+.1f bps) for %s".formatted(
                        actualRaroc * 100, origination.getProjectedRaroc() * 100, variance * 10000, t.getPeriodLabel()),
                Map.of("actualRaroc", actualRaroc, "variance", variance, "period", t.getPeriodLabel()));
        return saved;
    }

    @Transactional(readOnly = true)
    public List<RarocTracking> history(String reference) {
        return tracking.findByApplicationReferenceOrderByComputedAtAsc(reference);
    }

    @Transactional(readOnly = true)
    public RarocTracking latest(String reference) {
        return tracking.findFirstByApplicationReferenceAndOriginationFalseOrderByComputedAtDesc(reference)
                .or(() -> tracking.findFirstByApplicationReferenceAndOriginationTrueOrderByComputedAtAsc(reference))
                .orElseThrow(() -> ApiException.notFound("No RAROC tracking for " + reference));
    }

    /** Book-level variance: deals with |variance pct| > 25% are model-fit candidates. */
    @Transactional(readOnly = true)
    public Map<String, Object> bookVariance() {
        List<RarocTracking> latests = tracking.findAll().stream()
                .filter(t -> !t.isOrigination())
                .toList();
        int count = latests.size();
        double avgVariance = count == 0 ? 0.0 : latests.stream().mapToDouble(RarocTracking::getVariance).average().orElse(0);
        long belowProjected = latests.stream().filter(t -> t.getVariance() < 0).count();
        long materialMisses = latests.stream().filter(t -> t.getAbsVariancePct() > 0.25).count();
        return Map.of(
                "trackedDeals", count,
                "averageVariance", round4(avgVariance),
                "belowProjected", belowProjected,
                "materialMisses", materialMisses,
                "materialAlertThreshold", 0.25,
                "note", "Material miss = |actual − projected| / projected > 25%; feeds model-fit governance");
    }

    private double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    private double round4(double v) {
        return Math.round(v * 1_000_000.0) / 1_000_000.0;
    }
}
