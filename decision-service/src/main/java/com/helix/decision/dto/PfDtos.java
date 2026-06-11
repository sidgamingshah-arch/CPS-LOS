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
}
