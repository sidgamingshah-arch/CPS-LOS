package com.helix.portfolio.dto;

/** Vendor-shaped (raw) payloads accepted by portfolio connectors. */
public final class IngestDtos {

    private IngestDtos() {
    }

    /** Raw core-banking facility/conduct feed (pre-canonical field names). */
    public record RawCoreBankingFeed(String facilityRef, double sanctionedLimit, double outstanding,
                                     String currency, int overdueDays, Double conductRating, String accountStatus) {
    }
}
