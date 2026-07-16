package com.helix.common.query;

/**
 * Extension point for owning services to react when a {@link QueryThread} is resolved —
 * e.g. unblock a stalled stage, mark a requested document received, or clear a checklist
 * item. Any Spring bean in a service that implements this interface is invoked by
 * {@link QueryService#resolve} <b>in the same transaction</b> as the resolution, best-effort
 * (each listener is wrapped in try/catch so one listener can never break the resolve or the
 * SQLite single-writer transaction).
 *
 * <p>helix-common intentionally ships no concrete implementation; a service opts in later by
 * declaring its own {@code @Component} that implements this interface.</p>
 */
public interface QueryResolutionListener {

    /** Called after the thread has been persisted RESOLVED, within the resolve transaction. */
    void onResolved(QueryThread thread);
}
