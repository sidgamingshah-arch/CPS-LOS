package com.helix.common.export;

import java.time.Instant;
import java.util.List;

/**
 * Canonical OUTBOUND export contracts — the symmetric counterpart to {@code
 * com.helix.common.ingest}. A producing service builds an {@link Envelope} of typed
 * canonical records for a {@link DownstreamSystem}; the envelope carries an
 * idempotency key (so a re-run for the same as-of day is a no-op) and a payload
 * version. The typed records below are the agreed wire shapes for each consumer.
 */
public final class Export {

    private Export() {
    }

    /** A batch of canonical records bound for one downstream system. */
    public record Envelope<T>(DownstreamSystem destination, String feedType, String idempotencyKey,
                              String payloadVersion, Instant generatedAt, int recordCount, List<T> records) {
        public static <T> Envelope<T> of(DownstreamSystem destination, String feedType, String idempotencyKey,
                                         String payloadVersion, List<T> records) {
            return new Envelope<>(destination, feedType, idempotencyKey, payloadVersion,
                    Instant.now(), records == null ? 0 : records.size(), records);
        }
    }

    /** ERM feed — one risk record per live obligor/exposure. */
    public record ErmRiskRecord(String obligorRef, String name, String segment, String jurisdiction,
                                String sector, String grade, double pd, double lgd, double ead, double rwa,
                                double capitalRequired, String eclStage, double ecl, int daysPastDue,
                                String currency) {
    }

    /** Finance / GL feed — a provisioning accounting entry per exposure. */
    public record FinanceProvisionEntry(String obligorRef, String glAccount, String stage,
                                        double provisionAmount, String policy, String currency, String asOf) {
    }

    /** CPR feed — a portfolio composition / concentration line. */
    public record CprPortfolioLine(String dimension, String bucket, double ead, double sharePct) {
    }

    /** Syndication feed — one statement line per participating lender. */
    public record SyndicationParticipantLine(String applicationRef, String participantRef, String name,
                                             String role, double commitment, double sharePct,
                                             double fundedToDate, double undrawn, double totalFees,
                                             String currency) {
    }
}
