package com.helix.common.notify;

import com.helix.common.audit.AuditService;
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

    public NotificationController(NotificationService notifications, AuditService audit) {
        this.notifications = notifications;
        this.audit = audit;
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

    /** Force-run both sweep jobs now (same code path as the scheduled sweeper). */
    @PostMapping("/sweep")
    public Map<String, Object> sweep(@RequestHeader(value = "X-Actor", defaultValue = "system") String actor) {
        int dispatched = notifications.dispatchDueScheduled();
        int reminders = notifications.sweepReminders();
        audit.engine("NOTIFICATION_SWEEP", "Notification", "sweep",
                "Notification sweep dispatched %d scheduled row(s), created %d reminder(s)"
                        .formatted(dispatched, reminders),
                Map.of("dispatched", dispatched, "remindersCreated", reminders, "triggeredBy", actor));
        return Map.of("dispatched", dispatched, "remindersCreated", reminders);
    }
}
