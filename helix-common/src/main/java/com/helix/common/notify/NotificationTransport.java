package com.helix.common.notify;

/**
 * The pluggable outbound transport seam. The default {@link OutboxTransport} records
 * only (the persisted {@link Notification} row is the deliverable); a real SMTP / webhook
 * transport is a drop-in that provides its own {@code NotificationTransport} bean —
 * {@link OutboxTransport} steps aside via {@code @ConditionalOnMissingBean}.
 */
public interface NotificationTransport {

    /** Transport name recorded on the notification (e.g. OUTBOX, SMTP, WEBHOOK). */
    String name();

    /** Dispatch the (already-persisted, already-rendered) notification. Must not throw. */
    Result dispatch(Notification n);

    /** Outcome of a dispatch attempt. */
    record Result(String status, String providerRef, String failureReason) {
        public static Result sent(String providerRef) {
            return new Result("SENT", providerRef, null);
        }

        public static Result failed(String reason) {
            return new Result("FAILED", null, reason);
        }
    }
}
