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
}
