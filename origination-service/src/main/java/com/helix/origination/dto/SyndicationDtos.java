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
}
