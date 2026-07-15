package com.helix.common.query;

/**
 * Collaboration lane for a {@link QueryThread}. INTERNAL threads are in-app (visible via the
 * addressee inbox); the EXTERNAL_* lanes dispatch an RFI through the governed notification
 * façade (no real transport — the persisted outbox row IS the deliverable).
 */
public enum QueryChannel {
    INTERNAL,
    EXTERNAL_CUSTOMER,
    EXTERNAL_VENDOR
}
