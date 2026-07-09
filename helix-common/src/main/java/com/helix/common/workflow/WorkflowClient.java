package com.helix.common.workflow;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Best-effort client to the workflow tracker. Domain services CALL the tracker
 * at their existing transition points to report that a lifecycle stage happened
 * — they are not driven BY it. So every call is wrapped to log-and-skip on
 * failure (mirrors the {@code allocateSyndicationOrSkip} pattern in
 * {@code decision-service}); a workflow-service outage can never block the
 * credit lifecycle.
 *
 * <p>The bean activates only when {@code helix.workflow-service.base-url} is
 * set — services without the property simply don't get the client wired.</p>
 */
@Component
@ConditionalOnProperty(name = "helix.workflow-service.base-url")
public class WorkflowClient {

    private static final Logger log = LoggerFactory.getLogger(WorkflowClient.class);

    private final RestClient client;

    public WorkflowClient(@Value("${helix.workflow-service.base-url}") String baseUrl) {
        this.client = RestClient.builder().baseUrl(baseUrl).build();
    }

    /** Materialise an instance for a new application; idempotent on applicationReference. */
    public void materialise(String applicationReference, String jurisdiction, String segment, String actor) {
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("applicationReference", applicationReference);
            body.put("jurisdiction", jurisdiction);
            body.put("segment", segment);
            client.post().uri("/api/workflow/instances")
                    .header("X-Actor", actor == null ? "system" : actor)
                    .body(body)
                    .retrieve().toBodilessEntity();
        } catch (Exception e) {
            log.warn("workflow materialise skipped for {} ({})", applicationReference, e.getMessage());
        }
    }

    /** Record that a stage happened (best-effort, idempotent on already-COMPLETE). */
    public void record(String applicationReference, String stageKey, String actorType,
                       String actor, String note) {
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("actorType", actorType);
            body.put("note", note);
            client.post().uri("/api/workflow/instances/{r}/stages/{k}/record",
                            applicationReference, stageKey)
                    .header("X-Actor", actor == null ? "system" : actor)
                    .body(body)
                    .retrieve().toBodilessEntity();
        } catch (Exception e) {
            log.warn("workflow stage-record skipped for {}/{} ({})",
                    applicationReference, stageKey, e.getMessage());
        }
    }
}
