package com.helix.decision.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

import java.util.List;

public class RepaymentDtos {

    /**
     * Manual recording of a repayment. {@code principalComponent} /
     * {@code interestComponent} optional: when both absent the whole amount is
     * treated as principal; when given they must sum to {@code amount}.
     */
    public record RecordRepaymentRequest(@NotBlank String facilityRef, @Positive double amount,
                                         Double principalComponent, Double interestComponent,
                                         String valueDate, String narrative) {
    }

    public record RejectRepaymentRequest(@NotBlank String reason) {
    }

    /** One row of the deterministic repayment schedule. Floating rows carry the per-period rate. */
    public record ScheduleRow(int periodNo, String dueDate, double openingBalance, double payment,
                              double principal, double interest, double closingBalance,
                              Double periodRate) {
    }

    /**
     * The computed (never persisted) repayment schedule for a facility's current
     * outstanding. Deterministic figure path: principal from the released-draw
     * ledger, rate from the pricing of record, tenor from the facility.
     */
    public record ScheduleView(String applicationReference, String facilityRef, String method,
                               String frequency, double principal, double annualRate, String rateSource,
                               int periods, double totalPayment, double totalInterest,
                               List<ScheduleRow> rows) {
    }

    /** Raw repayment event from the core-banking / servicing connector. */
    public record RawRepaymentEvent(String facilityRef, double amount, Double principalComponent,
                                    Double interestComponent, String currency, String valueDate,
                                    String externalRef) {
    }

    /** Inbound connector envelope (mirrors Ingestion.Envelope for JSON binding). */
    public record RepaymentIngestEnvelope(String vendor, @NotBlank String idempotencyKey,
                                          String payloadVersion, RawRepaymentEvent payload) {
    }
}
