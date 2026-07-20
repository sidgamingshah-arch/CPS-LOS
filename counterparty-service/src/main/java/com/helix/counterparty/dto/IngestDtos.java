package com.helix.counterparty.dto;

import java.util.List;

/** Vendor-shaped (raw) payloads accepted by counterparty connectors. */
public final class IngestDtos {

    private IngestDtos() {
    }

    /** Raw sanctions/screening vendor payload (pre-canonical field names). */
    public record RawScreeningPayload(String entityName, List<RawMatch> matches) {
        public record RawMatch(String list, String name, double score, String risk, List<String> fields) {
        }
    }

    /**
     * Raw credit-bureau vendor payload (pre-canonical field names — CIBIL / Experian / Equifax
     * style). Mapped onto {@link com.helix.common.ingest.Canonical.BureauReport} by BureauConnector.
     */
    public record RawBureauPayload(String bureauName, String subjectName, String subjectId,
                                   Integer score, String scoreModel, Integer enquiries6m,
                                   Integer delinquencies24m, Integer tradelines, Double outstanding,
                                   Integer oldestAcctMonths) {
    }

    /**
     * Raw inbound CRM vendor payload (pre-canonical field names). Mapped onto
     * {@link com.helix.common.ingest.Canonical.CrmProfile} by CrmInboundConnector.
     *
     * <p>The trailing identity block is used only by the CRM <b>pull-and-create</b> path
     * (CrmObligorPullService) — it maps onto {@code CreateProspectRequest} so a borrower pulled
     * from CRM is created through the governed credit-initiation flow. These fields are optional
     * and null-safe for the ENRICH path (an existing CRM feed omits them; Jackson leaves them null
     * and the canonical connector mapping never reads them).
     */
    public record RawCrmPayload(String crmId, String accountName, String relationshipManager,
                                String segment, Double relationshipValue, String primaryContactName,
                                String primaryContactEmail, List<String> productsHeld, String lifecycleStage,
                                // ---- identity fields for governed prospect CREATION (pull-and-create) ----
                                String legalName, String borrowerType, String jurisdiction, String country,
                                String registrationNo, String pan, String gstin, String lei, String cin) {
    }
}
