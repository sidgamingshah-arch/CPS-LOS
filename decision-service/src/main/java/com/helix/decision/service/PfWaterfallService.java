package com.helix.decision.service;

import com.helix.common.audit.AuditService;
import com.helix.common.web.ApiException;
import com.helix.decision.client.UpstreamClient;
import com.helix.decision.client.UpstreamClient.DealEnvelopeDto;
import com.helix.decision.client.UpstreamClient.FacilityViewDto;
import com.helix.decision.dto.PfDtos.WaterfallProjection;
import com.helix.decision.dto.PfDtos.WaterfallRow;
import com.helix.decision.dto.PfDtos.WaterfallSummary;
import com.helix.decision.dto.RepaymentDtos.ScheduleRow;
import com.helix.decision.dto.RepaymentDtos.ScheduleView;
import com.helix.decision.entity.PfReserveAccount;
import com.helix.decision.repo.PfReserveAccountRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Project-finance payment waterfall + forward DSCR projection. Composes on top
 * of the existing repayment schedule (RepaymentService) and the live reserve
 * accounts: a deterministic projection of how a borrower's CFADS would be
 * spent each period under the contractual priority stack, what DSCR that
 * produces, and where it breaches.
 *
 * <p><b>Priority stack</b> per period:
 * <ol>
 *   <li><b>O&amp;M</b> — operations &amp; maintenance, modelled as a flat fraction
 *       of CFADS (regime / project specific; 30% default).</li>
 *   <li><b>Senior debt service</b> — principal + interest from the repayment
 *       schedule row.</li>
 *   <li><b>DSRA top-up</b> — if the debt-service reserve has slipped below its
 *       required minimum, the next 1× period's debt service refills it (capped
 *       at the requirement).</li>
 *   <li><b>MMRA top-up</b> — major-maintenance reserve, same shape as DSRA when
 *       defined.</li>
 *   <li><b>Distributions</b> — anything left flows to sponsors.</li>
 * </ol>
 *
 * <p>DSCR = (CFADS - O&amp;M) / debt service. Forward DSCR is the projection over
 * the schedule's remaining periods; the summary reports min / avg / LLCR
 * (loan-life coverage = NPV of CFADS net of O&amp;M, discounted at the schedule's
 * period rate, divided by current outstanding) / rolling-12 minimum / cushion
 * to the covenant.</p>
 *
 * <p>Computed view, never persisted — re-run any time the underlying schedule
 * or reserve state changes.</p>
 */
@Service
public class PfWaterfallService {

    private static final double DEFAULT_OM_RATIO = 0.30;
    private static final double DEFAULT_MIN_DSCR_COVENANT = 1.20;
    private static final double DEFAULT_CFADS_RAMP = 1.0;
    private static final int FORWARD_WINDOW = 12;

    private final RepaymentService repayments;
    private final PfReserveAccountRepository reserves;
    private final UpstreamClient upstream;
    private final AuditService audit;

    public PfWaterfallService(RepaymentService repayments, PfReserveAccountRepository reserves,
                              UpstreamClient upstream, AuditService audit) {
        this.repayments = repayments;
        this.reserves = reserves;
        this.upstream = upstream;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public WaterfallProjection project(String applicationReference, String facilityRef,
                                       double baseAnnualCfads, Double omRatioOverride,
                                       Double minDscrCovenantOverride, Double cfadsRampFactor,
                                       String frequency, String method, String actor) {
        if (baseAnnualCfads <= 0) {
            throw ApiException.badRequest("baseAnnualCfads must be positive");
        }
        double omRatio = omRatioOverride == null ? DEFAULT_OM_RATIO : omRatioOverride;
        if (omRatio < 0 || omRatio >= 1) {
            throw ApiException.badRequest("omRatio must be in [0, 1)");
        }
        double covenant = minDscrCovenantOverride == null ? DEFAULT_MIN_DSCR_COVENANT : minDscrCovenantOverride;
        double ramp = cfadsRampFactor == null ? DEFAULT_CFADS_RAMP : cfadsRampFactor;

        // The schedule is the contractual debt-service stream we run the waterfall
        // against. Frequency / method default to whatever the repayment schedule
        // does by default (monthly EMI) — the projection inherits both so debt
        // service and CFADS periods align by construction.
        ScheduleView schedule = repayments.schedule(applicationReference, facilityRef,
                method, frequency);
        int periodMonths = "QUARTERLY".equalsIgnoreCase(schedule.frequency()) ? 3 : 1;
        double periodFraction = periodMonths / 12.0;

        // Current reserve state for DSRA / MMRA — initial shortfall feeds period 1.
        double dsraRequired = 0, dsraBalance = 0;
        double mmraRequired = 0, mmraBalance = 0;
        for (PfReserveAccount r : reserves.findByApplicationReferenceOrderByIdAsc(applicationReference)) {
            if ("DSRA".equalsIgnoreCase(r.getAccountType())) {
                dsraRequired = com.helix.common.money.Money.asDouble(r.getRequiredAmount());
                dsraBalance = com.helix.common.money.Money.asDouble(r.getCurrentBalance());
            } else if ("MMRA".equalsIgnoreCase(r.getAccountType())
                    || "MAJOR_MAINTENANCE".equalsIgnoreCase(r.getAccountType())) {
                mmraRequired = com.helix.common.money.Money.asDouble(r.getRequiredAmount());
                mmraBalance = com.helix.common.money.Money.asDouble(r.getCurrentBalance());
            }
        }

        List<WaterfallRow> rows = new ArrayList<>();
        double sumDscr = 0;
        double minDscr = Double.POSITIVE_INFINITY;
        int firstBreach = -1;
        int totalBreaches = 0;
        double llcrNumerator = 0;
        double periodRate = schedule.annualRate() * periodFraction;

        for (int i = 0; i < schedule.rows().size(); i++) {
            ScheduleRow s = schedule.rows().get(i);
            double cfads = round2(baseAnnualCfads * periodFraction * cfadsForPeriod(i, schedule.rows().size(), ramp));
            double om = round2(cfads * omRatio);
            double cashAvailable = cfads - om;
            double debtService = round2(s.payment());

            // DSCR is the COVERAGE figure — coverage of contractual debt service
            // by cash available; not affected by the actual waterfall outcome
            // (reserve draws etc.). Cash shortfall captures the actual-payment gap.
            double dscr = debtService <= 0 ? Double.POSITIVE_INFINITY : (cashAvailable / debtService);
            sumDscr += Double.isFinite(dscr) ? dscr : 0;
            if (Double.isFinite(dscr) && dscr < minDscr) minDscr = dscr;

            // Walk the waterfall. Negative remaining = cash shortfall on debt service.
            double remaining = cashAvailable - debtService;
            double cashShortfall = remaining < 0 ? round2(-remaining) : 0.0;
            if (remaining < 0) remaining = 0;

            // DSRA top-up — refill toward required, capped at remaining cash and 1×
            // period debt service (typical "next period" build-up convention).
            double dsraTopUp = 0;
            if (dsraRequired > 0 && dsraBalance < dsraRequired - 1e-6) {
                double need = dsraRequired - dsraBalance;
                double cap = Math.min(need, debtService);
                dsraTopUp = round2(Math.min(remaining, cap));
                dsraBalance += dsraTopUp;
                remaining -= dsraTopUp;
            }
            double mmraTopUp = 0;
            if (mmraRequired > 0 && mmraBalance < mmraRequired - 1e-6) {
                double need = mmraRequired - mmraBalance;
                double cap = Math.min(need, debtService * 0.5);
                mmraTopUp = round2(Math.min(remaining, cap));
                mmraBalance += mmraTopUp;
                remaining -= mmraTopUp;
            }
            double distributions = round2(remaining);

            // LLCR numerator — NPV of (CFADS - O&M) using the schedule's period rate.
            llcrNumerator += cashAvailable / Math.pow(1 + periodRate, i + 1);

            boolean cashBreach = cashShortfall > 0;
            boolean covenantBreach = cashBreach || (Double.isFinite(dscr) && dscr < covenant - 1e-9);
            if (covenantBreach) {
                if (firstBreach < 0) firstBreach = s.periodNo();
                totalBreaches++;
            }
            rows.add(new WaterfallRow(s.periodNo(), s.dueDate(),
                    cfads, om, debtService, dsraTopUp, mmraTopUp, distributions,
                    round4(Double.isFinite(dscr) ? dscr : 0), cashShortfall,
                    covenantBreach, cashBreach));
        }

        double avgDscr = rows.isEmpty() ? 0 : sumDscr / rows.size();
        // Rolling forward-12 minimum: the worst 12-period average from any starting
        // period (matches the "forward DSCR" covenant pattern in PF deals).
        double rollingMin = rollingForwardMin(rows, FORWARD_WINDOW);
        double outstanding = repayments.outstandingPrincipal(applicationReference, facilityRef);
        double llcr = outstanding <= 0 ? Double.POSITIVE_INFINITY : llcrNumerator / outstanding;
        double cushion = minDscr <= 0 || covenant <= 0 ? 0 : (minDscr - covenant) / covenant;

        WaterfallSummary summary = new WaterfallSummary(
                round4(Double.isFinite(minDscr) ? minDscr : 0),
                round4(avgDscr),
                round4(Double.isFinite(llcr) ? llcr : 0),
                round4(rollingMin),
                round4(cushion),
                firstBreach,
                totalBreaches);

        // The projection is advisory (deterministic figure on top of the schedule,
        // not a credit-consequential figure that mutates a record). Audit-stamp
        // anyway so a regulator can see who pulled it, when, with what CFADS.
        audit.engine("PF_WATERFALL_PROJECTED", "Application", applicationReference,
                ("Projected waterfall on %s: %d periods, min DSCR %.3f, avg %.3f, LLCR %.3f, %d breach(es)"
                        + (firstBreach > 0 ? " (first at period " + firstBreach + ")" : ""))
                        .formatted(facilityRef, rows.size(),
                                summary.minDscr(), summary.avgDscr(), summary.llcr(), totalBreaches),
                Map.of("facilityRef", facilityRef,
                        "baseAnnualCfads", baseAnnualCfads,
                        "omRatio", omRatio,
                        "minDscrCovenant", covenant,
                        "minDscr", summary.minDscr(),
                        "breaches", totalBreaches));
        return new WaterfallProjection(applicationReference, facilityRef,
                schedule.frequency(), rows.size(),
                baseAnnualCfads, omRatio, covenant, schedule.rows().isEmpty()
                        ? "INR" : currencyFor(applicationReference, facilityRef),
                rows, summary);
    }

    // ============================================================ helpers

    /**
     * Default CFADS shape: flat (factor = 1) for the whole tenor. A {@code ramp}
     * factor below 1 phases revenue up linearly across the first 20% of the
     * tenor — useful for projects with a ramp-up where CFADS is depressed in
     * early periods (stress demo). Above 1 escalates over time.
     */
    private static double cfadsForPeriod(int idx, int total, double ramp) {
        if (ramp == 1.0) return 1.0;
        int rampPeriods = Math.max(1, total / 5);
        if (ramp < 1.0 && idx < rampPeriods) {
            double progress = (idx + 1.0) / rampPeriods;
            return ramp + (1.0 - ramp) * progress;
        }
        if (ramp > 1.0) {
            return 1.0 + (ramp - 1.0) * ((double) idx / Math.max(1, total - 1));
        }
        return 1.0;
    }

    private double rollingForwardMin(List<WaterfallRow> rows, int window) {
        if (rows.size() < window) return averageDscr(rows);
        double min = Double.POSITIVE_INFINITY;
        for (int start = 0; start + window <= rows.size(); start++) {
            double sum = 0;
            for (int k = start; k < start + window; k++) sum += rows.get(k).dscr();
            double avg = sum / window;
            if (avg < min) min = avg;
        }
        return Double.isFinite(min) ? min : 0;
    }

    private static double averageDscr(List<WaterfallRow> rows) {
        return rows.isEmpty() ? 0 : rows.stream().mapToDouble(WaterfallRow::dscr).average().orElse(0);
    }

    private String currencyFor(String applicationReference, String facilityRef) {
        DealEnvelopeDto env = upstream.envelope(applicationReference);
        if (env == null || env.facilities() == null) return "INR";
        for (FacilityViewDto f : env.facilities()) {
            if (facilityRef.equals(f.reference())) return f.currency();
        }
        return "INR";
    }

    private static double round2(double v) { return Math.round(v * 100.0) / 100.0; }
    private static double round4(double v) { return Math.round(v * 10_000.0) / 10_000.0; }
}
