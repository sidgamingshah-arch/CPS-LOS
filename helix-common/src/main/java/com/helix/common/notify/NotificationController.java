package com.helix.common.notify;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Read-only notification outbox endpoints, automatically present in every service that
 * includes helix-common (like {@code /api/audit}). Lets an examiner / UI see exactly what
 * was rendered and dispatched, filter by status/event/subject, and drill into one row.
 */
@RestController
@RequestMapping("/api/notifications")
public class NotificationController {

    private final NotificationService notifications;

    public NotificationController(NotificationService notifications) {
        this.notifications = notifications;
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
}
