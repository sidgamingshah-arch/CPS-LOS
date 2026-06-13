package com.helix.decision.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

import java.util.List;

public class PfDtos {

    public record DefineMilestoneRequest(@NotBlank String facilityRef, int sequence, @NotBlank String name,
                                         @Positive double plannedAmount, String currency,
                                         String plannedDate) {
    }

    public record CertifyRequest(@NotBlank String certificationRef, String note) {
    }

    public record DefineReserveRequest(@NotBlank String accountType, @Positive double requiredAmount,
                                       String currency) {
    }

    public record ReserveTxnRequest(@Positive double amount, String note) {
    }

    public record PfBlocker(String kind, String code, String detail) { }

    /** PF drawdown gate state for one facility — milestones + reserves. */
    public record PfGateResult(String facilityRef, Integer milestoneSequence, boolean canDrawdown,
                               List<PfBlocker> blockers) {
    }

    // ============================================================ waterfall + DSCR

    /**
     * One period of the projected payment waterfall. Cash uses are applied in
     * priority order (O&amp;M → senior debt service → DSRA top-up → MMRA top-up →
     * distributions); a level only consumes what is left after the level above
     * it has been fully served. {@code cashShortfall} is non-zero when CFADS net
     * of O&amp;M is below debt service — the borrower can't pay everything
     * contractually due. {@code covenantBreach} = DSCR below the covenant; a
     * cash shortfall always implies a covenant breach.
     */
    public record WaterfallRow(int periodNo, String periodDate,
                               double cfads, double om, double debtService,
                               double dsraTopUp, double mmraTopUp, double distributions,
                               double dscr, double cashShortfall,
                               boolean covenantBreach, boolean cashBreach) {
    }

    /** Headline DSCR / LLCR / cushion stats over the whole projection. */
    public record WaterfallSummary(double minDscr, double avgDscr, double llcr,
                                   double rollingForward12MinDscr,
                                   double cushionToCovenantPct,
                                   int firstBreachPeriod, int totalBreachPeriods) {
    }

    /** A whole projected waterfall — computed view, never persisted. */
    public record WaterfallProjection(String applicationReference, String facilityRef,
                                      String frequency, int periods,
                                      double baseAnnualCfads, double omRatio,
                                      double minDscrCovenant, String currency,
                                      List<WaterfallRow> rows, WaterfallSummary summary) {
    }

    public record WaterfallRequest(@NotBlank String facilityRef,
                                   @Positive double baseAnnualCfads,
                                   Double omRatio,
                                   Double minDscrCovenant,
                                   Double cfadsRampFactor,
                                   String frequency, String method) {
    }
}
