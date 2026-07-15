package com.helix.common.workflow;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Best-effort client to the case-management (task) layer in workflow-service.
 * Domain services CALL this to <b>mirror</b> a task at their existing transition
 * points — the authoritative domain status machines are never driven by it. Every
 * call is wrapped to log-and-skip on failure (mirrors {@link WorkflowClient}); a
 * workflow-service outage can never break the credit lifecycle, and a mirror call
 * NEVER throws into the caller's transaction.
 *
 * <p>The bean activates only when {@code helix.workflow-service.base-url} is set —
 * services without the property simply don't get the client wired.</p>
 */
@Component
@ConditionalOnProperty(name = "helix.workflow-service.base-url")
public class TaskClient {

    private static final Logger log = LoggerFactory.getLogger(TaskClient.class);

    private final RestClient client;

    public TaskClient(@Value("${helix.workflow-service.base-url}") String baseUrl) {
        this.client = RestClient.builder().baseUrl(baseUrl).build();
    }

    /**
     * Mirror a task for a subject. Idempotent on
     * ({@code subjectType},{@code subjectRef},{@code taskType},{@code dedupeKey}) —
     * re-mirroring the same logical task is a no-op server-side.
     */
    public void createTask(String subjectType, String subjectRef, String taskType, String queueKey,
                           String assignee, String dedupeKey, Integer slaHours,
                           String actor, Map<String, Object> payload) {
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("subjectType", subjectType);
            body.put("subjectRef", subjectRef);
            body.put("taskType", taskType);
            body.put("queueKey", queueKey);
            body.put("assignee", assignee);
            body.put("dedupeKey", dedupeKey);
            body.put("slaHours", slaHours);
            body.put("actorType", "SYSTEM");
            body.put("payload", payload == null ? Map.of() : payload);
            client.post().uri("/api/tasks")
                    .header("X-Actor", actor == null ? "system" : actor)
                    .body(body)
                    .retrieve().toBodilessEntity();
        } catch (Exception e) {
            log.warn("task mirror skipped for {}/{}/{} ({})",
                    subjectType, subjectRef, taskType, e.getMessage());
        }
    }

    /**
     * Fan out N sibling tasks under one join group ({@code ALL} / {@code ANY} /
     * {@code QUORUM:n}). Each member is a map that may carry {@code queueKey},
     * {@code assignee}, {@code priority}, {@code slaHours}, {@code payload}.
     */
    public void fanout(String subjectType, String subjectRef, String taskType, String joinPolicy,
                       List<Map<String, Object>> members, String actor) {
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("subjectType", subjectType);
            body.put("subjectRef", subjectRef);
            body.put("taskType", taskType);
            body.put("joinPolicy", joinPolicy);
            body.put("actorType", "SYSTEM");
            body.put("members", members == null ? new ArrayList<>() : members);
            client.post().uri("/api/tasks/fanout")
                    .header("X-Actor", actor == null ? "system" : actor)
                    .body(body)
                    .retrieve().toBodilessEntity();
        } catch (Exception e) {
            log.warn("task fan-out skipped for {}/{}/{} ({})",
                    subjectType, subjectRef, taskType, e.getMessage());
        }
    }

    /** Best-effort complete of a mirrored task by its {@code TSK-} ref. */
    public void complete(String taskRef, String note, String actor) {
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("note", note);
            client.post().uri("/api/tasks/{r}/complete", taskRef)
                    .header("X-Actor", actor == null ? "system" : actor)
                    .body(body)
                    .retrieve().toBodilessEntity();
        } catch (Exception e) {
            log.warn("task complete skipped for {} ({})", taskRef, e.getMessage());
        }
    }
}
