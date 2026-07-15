package com.helix.common.notify;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Recurring notification sweep, automatically present in every service that includes
 * helix-common (disable with {@code helix.notify.sweep-enabled=false}). Two jobs, each in
 * its own short transaction (SQLite pool=1 — mirror of the workflow SLA sweep pattern):
 * (a) dispatch SCHEDULED rows whose {@code scheduledFor} has arrived, and (b) enqueue
 * auto-reminder rows for reminder-eligible notifications. Every job is wrapped in
 * try/catch — a sweep failure must never break the app or the scheduler.
 *
 * <p>{@code POST /api/notifications/sweep} triggers the same jobs deterministically for
 * tests/ops without waiting on the timer (and works even when this bean is disabled).</p>
 */
@Component
@ConditionalOnProperty(name = "helix.notify.sweep-enabled", havingValue = "true", matchIfMissing = true)
public class NotificationSweeper {

    private static final Logger log = LoggerFactory.getLogger(NotificationSweeper.class);

    private final NotificationService notifications;

    public NotificationSweeper(NotificationService notifications) {
        this.notifications = notifications;
    }

    @Scheduled(fixedDelayString = "${helix.notify.sweep-interval-ms:60000}",
            initialDelayString = "${helix.notify.sweep-initial-delay-ms:30000}")
    public void sweep() {
        try {
            int dispatched = notifications.dispatchDueScheduled();
            if (dispatched > 0) log.info("notification sweep dispatched {} scheduled row(s)", dispatched);
        } catch (Exception e) {
            log.warn("notification schedule sweep failed ({})", e.getMessage());
        }
        try {
            int reminders = notifications.sweepReminders();
            if (reminders > 0) log.info("notification sweep created {} reminder(s)", reminders);
        } catch (Exception e) {
            log.warn("notification reminder sweep failed ({})", e.getMessage());
        }
    }

    /**
     * Turns the {@code @Scheduled} method on in services that don't already enable
     * scheduling themselves (workflow-service does; duplicate enablement is harmless).
     */
    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    @ConditionalOnProperty(name = "helix.notify.sweep-enabled", havingValue = "true", matchIfMissing = true)
    static class SchedulingEnabler {
    }
}
