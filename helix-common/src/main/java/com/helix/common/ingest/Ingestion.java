package com.helix.common.ingest;

import java.time.Instant;
import java.util.List;

/**
 * The connector contract for ingesting data from external source systems (PRD §8).
 * Every connector is idempotent (keyed), surfaces failures rather than dropping them,
 * carries versioned payloads, and attaches provenance to every canonical figure.
 */
public final class Ingestion {

    private Ingestion() {
    }

    /** Where a canonical figure came from — carried through to the figure→source→version trace. */
    public record Provenance(SourceSystem sourceSystem, String vendor, String sourceReference,
                             String payloadVersion, Instant retrievedAt) {
        public static Provenance of(SourceSystem source, String vendor, String sourceReference, String version) {
            return new Provenance(source, vendor, sourceReference, version, Instant.now());
        }
    }

    /** Inbound wrapper: a versioned vendor payload with an idempotency key. */
    public record Envelope<T>(SourceSystem source, String vendor, String idempotencyKey,
                              String payloadVersion, T payload) {
    }

    /** Outcome of an ingestion — accepted/duplicate, with warnings surfaced, never silent. */
    public record Result(boolean accepted, boolean duplicate, SourceSystem source, String idempotencyKey,
                         String canonicalRef, String message, List<String> warnings) {
        public static Result accepted(SourceSystem source, String key, String canonicalRef, String message,
                                      List<String> warnings) {
            return new Result(true, false, source, key, canonicalRef, message, warnings);
        }

        public static Result duplicate(SourceSystem source, String key, String canonicalRef) {
            return new Result(true, true, source, key, canonicalRef,
                    "Idempotent replay — payload already ingested", List.of());
        }
    }

    /**
     * Maps a raw vendor payload onto a canonical object. Implementations own the field
     * mapping and validation; the surrounding service adds idempotency and persistence.
     */
    public interface Connector<RAW, CANON> {
        SourceSystem source();

        /** Validation warnings (e.g. missing optional fields). Empty = clean. */
        default List<String> validate(RAW raw) {
            return List.of();
        }

        CANON map(RAW raw, Provenance provenance);
    }
}
