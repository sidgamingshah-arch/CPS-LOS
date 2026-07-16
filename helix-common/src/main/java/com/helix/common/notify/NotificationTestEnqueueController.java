package com.helix.common.notify;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * TEST-ONLY affordance to enqueue a notification directly (with optional schedule-later and
 * reminder config) so an e2e can prove the sweep mechanics deterministically without driving
 * a whole business flow. Mirrors {@link com.helix.common.rbac.RbacOutageSimulationController}:
 * the whole bean is gated by {@code helix.notify.test-enqueue-enabled=true} and does NOT
 * exist in a production run (the property defaults false; scripts/run-all.sh enables it for
 * the local/e2e stack).
 */
@RestController
@ConditionalOnProperty(name = "helix.notify.test-enqueue-enabled", havingValue = "true")
public class NotificationTestEnqueueController {

    private final NotificationService notifications;

    public NotificationTestEnqueueController(NotificationService notifications) {
        this.notifications = notifications;
    }

    /**
     * @param scheduleAt        optional ISO-8601 instant for deferred dispatch
     * @param scheduleInSeconds optional relative alternative to {@code scheduleAt} (wins if both set)
     * @param reminderEveryHours optional explicit reminder cadence (needs {@code maxReminders} too)
     * @param maxReminders      optional explicit reminder cap
     */
    public record TestEnqueueRequest(String eventType, String templateKey, String subjectType,
                                     String subjectRef, String dedupeKey, String jurisdiction,
                                     Map<String, Object> vars, List<String> recipientRoles,
                                     String scheduleAt, Long scheduleInSeconds,
                                     Integer reminderEveryHours, Integer maxReminders) {
    }

    @PostMapping("/api/notifications/_test-enqueue")
    public Notification enqueue(@RequestBody TestEnqueueRequest req,
                                @RequestHeader(value = "X-Actor", defaultValue = "e2e.test") String actor) {
        Instant scheduleAt = null;
        if (req.scheduleInSeconds() != null) {
            scheduleAt = Instant.now().plusSeconds(req.scheduleInSeconds());
        } else if (req.scheduleAt() != null && !req.scheduleAt().isBlank()) {
            scheduleAt = Instant.parse(req.scheduleAt());
        }
        NotificationService.Enqueue cmd = new NotificationService.Enqueue(req.eventType(),
                req.templateKey(), req.subjectType(), req.subjectRef(), req.dedupeKey(),
                req.jurisdiction(), req.vars(), req.recipientRoles());
        return notifications.enqueue(cmd, actor, scheduleAt, req.reminderEveryHours(), req.maxReminders());
    }
}
