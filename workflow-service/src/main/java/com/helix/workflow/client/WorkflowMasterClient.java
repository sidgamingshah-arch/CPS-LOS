package com.helix.workflow.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Optional;

/**
 * Reads the case-management masters from config-service's generic Master-Data
 * engine ({@code /api/masters/{type}}). Two types drive assignment:
 * <ul>
 *   <li>{@code ASSIGNMENT_POOL} — keyed by {@code queueKey}, payload
 *       {@code {members, roles, supervisors, strategy, tatStartsOn, slaHours}}.</li>
 *   <li>{@code OOO_CALENDAR} — keyed by {@code actor}, payload
 *       {@code {from, to, delegateTo}}.</li>
 * </ul>
 * Degrades gracefully: a config-service outage yields an empty list (treat the
 * pool as empty / nobody OOO), exactly like the sibling {@code ConfigMasterClient}
 * pattern — assignment must never hard-fail on a masters outage.
 */
@Component
public class WorkflowMasterClient {

    private static final Logger log = LoggerFactory.getLogger(WorkflowMasterClient.class);

    private final RestClient config;

    public WorkflowMasterClient(@Value("${helix.config-service.base-url}") String baseUrl) {
        this.config = RestClient.builder().baseUrl(baseUrl).build();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record MasterRecordDto(Long id, String masterType, String recordKey, String jurisdiction,
                                  java.util.Map<String, Object> payload, String status, int version) {
    }

    public List<MasterRecordDto> listActive(String type) {
        try {
            MasterRecordDto[] arr = config.get().uri("/api/masters/{t}", type)
                    .retrieve().body(MasterRecordDto[].class);
            return arr == null ? List.of() : List.of(arr);
        } catch (Exception e) {
            log.warn("config-service master {} unavailable ({}) — treating as empty", type, e.getMessage());
            return List.of();
        }
    }

    /** The ASSIGNMENT_POOL record for a queue, if any is ACTIVE. */
    public Optional<MasterRecordDto> assignmentPool(String queueKey) {
        if (queueKey == null || queueKey.isBlank()) return Optional.empty();
        return listActive("ASSIGNMENT_POOL").stream()
                .filter(r -> queueKey.equalsIgnoreCase(r.recordKey()))
                .findFirst();
    }

    /** The OOO_CALENDAR record for an actor, if any is ACTIVE. */
    public Optional<MasterRecordDto> ooo(String actor) {
        if (actor == null || actor.isBlank()) return Optional.empty();
        return listActive("OOO_CALENDAR").stream()
                .filter(r -> actor.equalsIgnoreCase(r.recordKey()))
                .findFirst();
    }
}
