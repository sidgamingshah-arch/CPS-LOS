package com.helix.common.notify;

import org.springframework.stereotype.Component;

/**
 * Default transport: records only. Dispatch is a no-op that marks the notification SENT
 * with a synthetic provider reference — the durable, examiner-visible outbox row IS the
 * delivery. A bank swaps in a real SMTP/webhook transport by providing its own
 * {@link NotificationTransport} bean and excluding this one; {@link NotificationService}
 * consumes whichever single {@code NotificationTransport} is on the classpath.
 */
@Component
public class OutboxTransport implements NotificationTransport {

    @Override
    public String name() {
        return "OUTBOX";
    }

    @Override
    public Result dispatch(Notification n) {
        return Result.sent("outbox:" + n.getId());
    }
}
