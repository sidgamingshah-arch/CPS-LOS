package com.helix.common.dms;

/**
 * Document-store SPI (the "DMS" storage seam). Persists the raw bytes of an uploaded
 * document under an opaque, internally-generated {@code storageKey}; the searchable
 * metadata (subject, filename, size, sha256, uploader) lives in the {@code stored_document}
 * table regardless of which backend is active. Two backends ship:
 *
 * <ul>
 *   <li>{@link FilesystemDocumentStore} — DEFAULT, bytes under {@code ${HELIX_DATA_DIR}/documents};</li>
 *   <li>{@link S3DocumentStore} — product adapter for AWS S3 / S3-compatible object stores,
 *       enabled by {@code helix.dms.store=s3}.</li>
 * </ul>
 *
 * <p>The active backend is chosen by {@code helix.dms.store} ({@code filesystem} | {@code s3});
 * the default (missing property) is {@code filesystem}, so a fresh install stores locally with
 * no external dependency. Selection is {@code @ConditionalOnProperty} on each implementation, so
 * a bank drops in its own {@code DocumentStore} bean the same way it swaps the notification
 * transport.</p>
 */
public interface DocumentStore {

    /** Backend identifier recorded on the stored-document row (e.g. FILESYSTEM, S3). */
    String backend();

    /** Persist raw bytes under {@code storageKey}; returns a durable location + optional provider ref. */
    PutResult put(String storageKey, byte[] content, String contentType);

    /** Fetch the raw bytes previously stored under {@code storageKey}. */
    byte[] get(String storageKey);

    /** Outcome of a store operation: a human-readable location and any backend provider reference. */
    record PutResult(String location, String providerRef) {
    }
}
