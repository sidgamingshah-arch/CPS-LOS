package com.helix.origination.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

import java.util.List;

public class SyndicationDtos {

    /** One lender's line in the syndicate book. */
    public record LenderLine(Long participantId, String name, String externalRef, String role,
                             double commitment, double sharePct, double fundedToDate, double undrawn,
                             FeeBreakdown fees) {
    }

    public record FeeBreakdown(double arrangementFee, double underwritingFee, double agencyFee,
                               double participationFee, double totalFee) {
    }

    /** The full syndicate view for a deal. */
    public record SyndicateBook(String applicationReference, String currency, double facilityAmount,
                                double totalCommitment, double totalFunded, boolean fullySubscribed,
                                List<LenderLine> lenders, FeeBreakdown feeTotals) {
    }

    public record AllocateRequest(@NotBlank String drawdownRef, @Positive double amount, String currency) {
    }

    public record AllocationLine(Long participantId, String participantName, String role,
                                 double sharePct, double allocatedAmount, String currency) {
    }

    public record AllocationResult(String applicationReference, String drawdownRef, double drawdownAmount,
                                   double allocatedTotal, boolean reused, List<AllocationLine> lines) {
    }

    /**
     * At-a-glance summary of a syndicated deal — lets the UI list ONLY SYNDICATION
     * deals (so a picker never offers a non-syndicated app that would yield an empty
     * book). {@code totalCommitment} and {@code lenderCount} are derived from the
     * captured lender participants; borrower/currency come from the application.
     */
    public record SyndicatedDealSummary(String reference, String borrower, String currency,
                                        double totalCommitment, int lenderCount) {
    }

    // ============================================================ information memorandum

    /** Create a DRAFT Information Memorandum for a syndication deal. {@code title} optional. */
    public record CreateImRequest(String title) {
    }

    /** Upsert a single IM section by {@code key} (e.g. RISK_FACTORS) with free-text {@code content}. */
    public record ImSectionRequest(@NotBlank String key, String content) {
    }
}
