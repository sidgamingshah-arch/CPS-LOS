package com.helix.common.crm;

import com.helix.common.export.Export;

/**
 * CRM write-back connector SPI — the outbound counterpart, on the canonical
 * {@link com.helix.common.export.Export} contract, for pushing case/decision status back to
 * the originating CRM/source system (PRD "back-updation to CRM"). Two modes ship, selected by
 * {@code helix.crm.mode}:
 *
 * <ul>
 *   <li>{@link SimulatedCrmConnector} — DEFAULT ({@code simulated}). Records the would-be call
 *       only; the persisted {@code crm_write_back} row IS the deliverable, exactly like today's
 *       downstream export façade. No network egress.</li>
 *   <li>{@link LiveCrmConnector} — {@code live}. Performs a real {@code RestClient} POST of the
 *       canonical envelope to {@code helix.crm.base-url} with a configurable auth header.</li>
 * </ul>
 *
 * The pushed status is deterministic and already authoritative; the connector never computes or
 * alters a figure — it only relays.
 */
public interface CrmConnector {

    /** Active mode recorded on the write-back row (SIMULATED | LIVE). */
    String mode();

    /** Relay the canonical case-status envelope to the CRM. Must not throw — returns a Result. */
    Result push(Export.Envelope<Export.CrmCaseStatusRecord> envelope);

    /** Outcome of a write-back attempt. */
    record Result(String deliveryStatus, String providerRef, String failureReason) {
        public static Result recorded() {
            return new Result("SIMULATED", null, null);
        }

        public static Result delivered(String providerRef) {
            return new Result("DELIVERED", providerRef, null);
        }

        public static Result failed(String reason) {
            return new Result("FAILED", null, reason);
        }
    }
}
