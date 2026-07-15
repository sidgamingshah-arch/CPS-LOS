package com.helix.common.notify;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface NotificationRepository extends JpaRepository<Notification, Long> {

    Optional<Notification> findByIdempotencyKey(String idempotencyKey);

    List<Notification> findTop200ByOrderByIdDesc();

    List<Notification> findByStatusOrderByIdDesc(String status);

    List<Notification> findByEventTypeOrderByIdDesc(String eventType);

    List<Notification> findBySubjectTypeAndSubjectRefOrderByIdDesc(String subjectType, String subjectRef);

    /** SCHEDULED rows whose deferred dispatch time has arrived (sweep job a). */
    List<Notification> findByStatusAndScheduledForLessThanEqualOrderByIdAsc(String status, Instant cutoff);

    /** Reminder-eligible rows in a given status (sweep job b filters cadence/cap in code). */
    List<Notification> findByStatusAndReminderEveryHoursIsNotNullOrderByIdAsc(String status);
}
