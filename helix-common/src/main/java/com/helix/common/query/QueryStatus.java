package com.helix.common.query;

/**
 * Lifecycle of a {@link QueryThread}. SCHEDULED holds a schedule-later thread until the
 * platform sweep releases it to OPEN (and dispatches its RFI, for external lanes). RESPONDED
 * signals the raiser that a reply landed; only the raiser may then move it to RESOLVED.
 */
public enum QueryStatus {
    SCHEDULED,
    OPEN,
    RESPONDED,
    RESOLVED,
    CANCELLED
}
