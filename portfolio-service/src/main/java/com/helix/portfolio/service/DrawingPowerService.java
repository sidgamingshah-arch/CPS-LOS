package com.helix.portfolio.service;

import com.helix.common.audit.AuditService;
import com.helix.common.web.ApiException;
import com.helix.portfolio.client.PortfolioUpstreamClient;
import com.helix.portfolio.client.PortfolioUpstreamClient.RulePackDto;
import com.helix.portfolio.dto.Dtos.BorrowingBaseRequest;
import com.helix.portfolio.entity.DrawingPowerAssessment;
import com.helix.portfolio.entity.ExposureRecord;
import com.helix.portfolio.repo.DrawingPowerAssessmentRepository;
import com.helix.portfolio.repo.ExposureRecordRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Working-capital drawing-power monitoring (RBI DP norms). Deterministic + advisory:
 * DP = stock×(1−stockMargin) + debtors×(1−debtorMargin) − creditors from the borrowing
 * base; the eligible drawing is min(DP, sanctioned limit). A shortfall (outstanding &gt;
 * eligible DP) is flagged for the watchlist. This never touches the limit ledger — the
 * authoritative utilisation figure is unchanged. Only active when the jurisdiction's
 * pack enables it ({@code drawing_power_enabled}); otherwise the endpoint is inert.
 */
@Service
public class DrawingPowerService {

    private final DrawingPowerAssessmentRepository repo;
    private final ExposureRecordRepository exposures;
    private final PortfolioUpstreamClient upstream;
    private final AuditService audit;

    public DrawingPowerService(DrawingPowerAssessmentRepository repo, ExposureRecordRepository exposures,
                               PortfolioUpstreamClient upstream, AuditService audit) {
        this.repo = repo;
        this.exposures = exposures;
        this.upstream = upstream;
        this.audit = audit;
    }

    @Transactional
    public DrawingPowerAssessment compute(String reference, BorrowingBaseRequest req, String actor) {
        ExposureRecord e = exposures.findByApplicationReference(reference)
                .orElseThrow(() -> ApiException.notFound("No booked exposure for " + reference));
        RulePackDto pack = upstream.pack(e.getJurisdiction(), "PROVISIONING",
                new RulePackDto("fallback_dp", 0, Map.of()));
        if (pack.number("drawing_power_enabled", 0) < 1) {
            throw ApiException.conflict(
                    "Drawing-power monitoring is not configured for jurisdiction " + e.getJurisdiction());
        }
        double stockMargin = pack.number("dp_stock_margin_pct", 0.25);
        double debtorMargin = pack.number("dp_debtor_margin_pct", 0.40);
        double dp = Math.max(0.0,
                req.stock() * (1 - stockMargin) + req.debtors() * (1 - debtorMargin) - req.creditors());
        double sanctioned = req.sanctionedLimit();
        double eligible = sanctioned > 0 ? Math.min(dp, sanctioned) : dp;
        double outstanding = req.outstanding();
        boolean capped = outstanding > eligible;
        double shortfall = Math.max(0.0, outstanding - eligible);

        DrawingPowerAssessment a = new DrawingPowerAssessment();
        a.setApplicationReference(reference);
        a.setFacilityRef(req.facilityRef());
        a.setStock(req.stock());
        a.setDebtors(req.debtors());
        a.setCreditors(req.creditors());
        a.setStockMarginPct(stockMargin);
        a.setDebtorMarginPct(debtorMargin);
        a.setDrawingPower(round2(dp));
        a.setSanctionedLimit(sanctioned);
        a.setOutstanding(outstanding);
        a.setShortfall(round2(shortfall));
        a.setCapped(capped);
        a.setAdvisory(true);
        a.setCurrency(req.currency() == null ? e.getCurrency() : req.currency());
        a.setProvisioningPackCode(pack.code());
        a.setProvisioningPackVersion(pack.version());
        Map<String, Object> comp = new LinkedHashMap<>();
        comp.put("stockEligible", round2(req.stock() * (1 - stockMargin)));
        comp.put("debtorsEligible", round2(req.debtors() * (1 - debtorMargin)));
        comp.put("creditors", req.creditors());
        comp.put("eligibleDrawingPower", round2(eligible));
        a.setComponents(comp);
        DrawingPowerAssessment saved = repo.save(a);

        audit.engine("DRAWING_POWER_COMPUTED", "Application", reference,
                "Drawing power %.0f (eligible %.0f) vs outstanding %.0f on %s — %s".formatted(
                        dp, eligible, outstanding, req.facilityRef(), capped ? "SHORTFALL" : "within DP"),
                Map.of("facilityRef", req.facilityRef(), "drawingPower", round2(dp),
                        "eligible", round2(eligible), "outstanding", outstanding,
                        "capped", capped, "shortfall", round2(shortfall), "advisory", true));
        return saved;
    }

    @Transactional(readOnly = true)
    public List<DrawingPowerAssessment> history(String reference, String facilityRef) {
        return facilityRef == null || facilityRef.isBlank()
                ? repo.findByApplicationReferenceOrderByIdDesc(reference)
                : repo.findByApplicationReferenceAndFacilityRefOrderByIdDesc(reference, facilityRef);
    }

    private double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
