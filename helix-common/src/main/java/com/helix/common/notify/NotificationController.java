package com.helix.common.notify;

import com.helix.common.audit.AuditService;
import com.helix.common.query.QueryService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Notification outbox endpoints, automatically present in every service that includes
 * helix-common (like {@code /api/audit}). Read side lets an examiner / UI see exactly what
 * was rendered and dispatched, filter by status/event/subject, and drill into one row.
 * {@code POST /sweep} force-runs the schedule-later + auto-reminder sweep deterministically
 * (SYSTEM-level action, audited) so tests/ops never have to wait on the timer.
 */
@RestController
@RequestMapping("/api/notifications")
public class NotificationController {

    private final NotificationService notifications;
    private final AuditService audit;
    private final ObjectProvider<QueryService> queries;

    public NotificationController(NotificationService notifications, AuditService audit,
                                  ObjectProvider<QueryService> queries) {
        this.notifications = notifications;
        this.audit = audit;
        this.queries = queries;
    }

    @GetMapping
    public List<Notification> list(@RequestParam(required = false) String status,
                                   @RequestParam(required = false) String eventType,
                                   @RequestParam(required = false) String subjectRef) {
        return notifications.list(status, eventType, subjectRef);
    }

    @GetMapping("/{id}")
    public Notification get(@PathVariable Long id) {
        return notifications.get(id);
    }

    // -------- read-state (notification-center); additive, does not change the list/enqueue surface

    /** Unread count for the notification bell, optionally scoped to a recipient and/or role. */
    @GetMapping("/unread-count")
    public Map<String, Object> unreadCount(@RequestParam(required = false) String recipient,
                                           @RequestParam(required = false) String role) {
        return Map.of("unread", notifications.unreadCount(recipient, role));
    }

    /** Mark one notification read (idempotent). Human action — carries X-Actor into the audit trail. */
    @PostMapping("/{id}/read")
    public Notification markRead(@PathVariable Long id,
                                 @RequestHeader(value = "X-Actor", defaultValue = "system") String actor) {
        return notifications.markRead(id, actor);
    }

    /** Mark all unread read (optionally scoped to a recipient); returns how many flipped. */
    @PostMapping("/read-all")
    public Map<String, Object> readAll(@RequestParam(required = false) String recipient,
                                       @RequestHeader(value = "X-Actor", defaultValue = "system") String actor) {
        return Map.of("read", notifications.markAllRead(recipient, actor));
    }

    /**
     * Force-run every sweep job now (same code path as the scheduled {@link NotificationSweeper}):
     * dispatch due scheduled notifications, spawn due reminders, and release due SCHEDULED
     * query/RFI threads (via the same {@code ObjectProvider<QueryService>} the sweeper uses, so the
     * manual endpoint matches its documented contract and query threads release deterministically).
     */
    @PostMapping("/sweep")
    public Map<String, Object> sweep(@RequestHeader(value = "X-Actor", defaultValue = "system") String actor) {
        int dispatched = notifications.dispatchDueScheduled();
        int reminders = notifications.sweepReminders();
        int queriesReleased = 0;
        QueryService q = queries.getIfAvailable();
        if (q != null) {
            queriesReleased = q.dispatchDueScheduled();
        }
        audit.engine("NOTIFICATION_SWEEP", "Notification", "sweep",
                "Notification sweep dispatched %d scheduled row(s), created %d reminder(s), released %d query thread(s)"
                        .formatted(dispatched, reminders, queriesReleased),
                Map.of("dispatched", dispatched, "remindersCreated", reminders,
                        "queriesReleased", queriesReleased, "triggeredBy", actor));
        return Map.of("dispatched", dispatched, "remindersCreated", reminders,
                "queriesReleased", queriesReleased);
    }
}
