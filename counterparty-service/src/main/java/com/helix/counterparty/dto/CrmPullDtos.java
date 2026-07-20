package com.helix.counterparty.dto;

import java.util.List;

/**
 * Request/result records for the CRM <b>pull-and-create</b> path — running CRM as the
 * system-of-record for obligor creation. A pull always creates a governed PROSPECT through the
 * existing credit-initiation flow (never a fully-approved obligor); dedup + idempotency guarantee
 * no duplicate obligors; a negative-list hit is flagged but never auto-approved.
 */
public final class CrmPullDtos {

    private CrmPullDtos() {
    }

    /** Pull a single borrower from CRM by its CRM id. */
    public record PullBorrowerRequest(String crmId) {
    }

    /**
     * Pull a batch of borrowers from CRM. When {@code crmIds} is null/empty the simulated source
     * returns its default sample list (live: the CRM list endpoint).
     */
    public record PullBatchRequest(List<String> crmIds) {
    }

    /**
     * Outcome of a single pull-and-create upsert.
     *
     * @param created         a NEW governed prospect was created through createProspect
     * @param matchedExisting the same CRM id had already been pulled (idempotent replay)
     * @param dedupMatches    number of existing counterparties the borrower's identifiers matched
     * @param negativeHit     the borrower is on the negative list (flagged; NOT auto-approved)
     * @param lifecycleStatus the resolved counterparty's lifecycle status (prospect-stage on create)
     * @param recordType      PROSPECT vs OBLIGOR — always PROSPECT immediately after a pull
     */
    public record CrmPullResult(String counterpartyRef, Long counterpartyId, boolean created,
                                boolean matchedExisting, int dedupMatches, boolean negativeHit,
                                String lifecycleStatus, String recordType, String crmId, String message) {
    }

    /** Summary of a batch pull — per-borrower results plus roll-up counts. */
    public record CrmPullBatchSummary(int total, int created, int matchedExisting, int dedupLinked,
                                      int negativeFlagged, List<CrmPullResult> results) {
    }
}
