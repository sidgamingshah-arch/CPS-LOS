package com.helix.origination.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.PositiveOrZero;

import java.util.List;

/** Request DTOs for the Banking ASR engine (/api/banking-asr). DTOs are records. */
public final class BankingAsrDtos {

    private BankingAsrDtos() {
    }

    /**
     * Create + deterministically compute a banking ASR from the posted monthly lines.
     * {@code sanctionedLimit} is the base the utilisation figures are computed against.
     */
    public record CreateAsrRequest(
            @NotBlank String applicationRef,
            @NotBlank String bankName,
            String accountNoMasked,
            @PositiveOrZero double sanctionedLimit,
            @NotBlank String currency,
            String periodFrom,
            String periodTo,
            @NotEmpty List<AsrLineRequest> lines) {
    }

    /**
     * One month of account conduct. {@code drawn} is the average drawn / outstanding amount
     * for the month; the service derives {@code utilisationPct = drawn ÷ sanctionedLimit}.
     * {@code chequeReturnsInward}/{@code chequeReturnsOutward} and {@code transactionCount}
     * are summed across months into the parent ASR's deterministic metrics.
     */
    public record AsrLineRequest(
            @NotBlank String monthLabel,
            double openingBalance,
            double closingBalance,
            double totalCredit,
            double totalDebit,
            double peakBalance,
            double minBalanceInMonth,
            double drawn,
            double chequeReturnsInward,
            double chequeReturnsOutward,
            int transactionCount) {
    }

    /** Optional note carried on confirm. */
    public record ConfirmRequest(String note) {
    }
}
