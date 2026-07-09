package com.helix.workflow.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.helix.workflow.dto.WorkflowDefinitionDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Reads the active {@code WORKFLOW_DEFINITION} rule pack for a jurisdiction and
 * picks the segment-matching definition out of its payload. Falls back to a
 * conservative built-in linear stage list when config-service is unreachable —
 * onboarding must never hard-fail because the engine is starting up.
 *
 * <p>The pack is structured as one envelope per (jurisdiction, segment) — see
 * {@code config-service/.../seed/RulePackSeeder.java#workflowMidCorporate}.
 * Hitting {@code /api/rulepacks?jurisdiction=X&type=WORKFLOW_DEFINITION} returns
 * the active definition for that jurisdiction; the segment is then matched
 * against the payload's {@code segment} key. When multiple definitions are
 * needed per jurisdiction (mid-corp + SME), they each surface as separate
 * versioned packs and we pick by segment.</p>
 */
@Component
public class DefinitionClient {

    private static final Logger log = LoggerFactory.getLogger(DefinitionClient.class);

    private final RestClient config;

    public DefinitionClient(@Value("${helix.config-service.base-url}") String configUrl) {
        this.config = RestClient.builder().baseUrl(configUrl).build();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PackDto(Long id, String code, int version, String type, String jurisdiction,
                          Map<String, Object> payload) {
    }

    /**
     * Best-effort pack lookup. We list packs for the jurisdiction (history would
     * include inactive versions; for v1 we read the single active for type
     * WORKFLOW_DEFINITION and rely on definition's {@code segment} key to
     * disambiguate). If the active pack's segment doesn't match the requested
     * one, we walk history. Returns the fallback if all lookups fail.
     */
    public WorkflowDefinitionDto resolve(String jurisdiction, String segment) {
        String seg = segment == null ? "" : segment.toUpperCase(Locale.ROOT);
        try {
            PackDto active = config.get().uri(uri -> uri.path("/api/rulepacks")
                            .queryParam("jurisdiction", jurisdiction)
                            .queryParam("type", "WORKFLOW_DEFINITION").build())
                    .retrieve().body(PackDto.class);
            WorkflowDefinitionDto matched = matchSegment(active, seg);
            if (matched != null) return matched;
            // Active pack didn't match the requested segment — walk history.
            PackDto[] history = config.get().uri(uri -> uri.path("/api/rulepacks/history")
                            .queryParam("jurisdiction", jurisdiction)
                            .queryParam("type", "WORKFLOW_DEFINITION").build())
                    .retrieve().body(PackDto[].class);
            if (history != null) {
                for (PackDto h : history) {
                    WorkflowDefinitionDto m = matchSegment(h, seg);
                    if (m != null) return m;
                }
            }
            log.warn("No WORKFLOW_DEFINITION pack matched segment {} for {} — using fallback", seg, jurisdiction);
            return fallback(jurisdiction, seg);
        } catch (Exception e) {
            log.warn("config-service unreachable for WORKFLOW_DEFINITION/{}/{} ({}) — using fallback",
                    jurisdiction, seg, e.getMessage());
            return fallback(jurisdiction, seg);
        }
    }

    private WorkflowDefinitionDto matchSegment(PackDto pack, String segment) {
        if (pack == null || pack.payload() == null) return null;
        Object payloadSegment = pack.payload().get("segment");
        if (payloadSegment != null && !segment.isEmpty()
                && !segment.equalsIgnoreCase(String.valueOf(payloadSegment))) {
            return null;
        }
        Object stagesObj = pack.payload().get("stages");
        if (!(stagesObj instanceof List<?> list)) return null;
        List<Map<String, Object>> stages = new java.util.ArrayList<>();
        for (Object o : list) {
            if (o instanceof Map<?, ?> m) {
                stages.add((Map<String, Object>) m);
            }
        }
        return new WorkflowDefinitionDto(pack.code(), pack.version(),
                String.valueOf(payloadSegment == null ? segment : payloadSegment),
                stages);
    }

    private WorkflowDefinitionDto fallback(String jurisdiction, String segment) {
        // A conservative, regulator-agnostic lifecycle so the engine always has
        // SOMETHING to materialise even if config-service is cold.
        List<Map<String, Object>> stages = List.of(
                stage("INTAKE", "Application intake", "—", false, true, 4),
                stage("KYC_CDD", "KYC / CDD / screening", "—", false, true, 24),
                stage("SPREADING", "Financial spreading", "—", false, false, 12),
                stage("RATING", "Scorecard rating", "—", false, true, 8),
                stage("PRICING", "Risk-adjusted pricing", "—", false, false, 4),
                stage("CREDIT_PROPOSAL", "Credit proposal", "—", false, false, 4),
                stage("APPROVAL", "DoA-routed approval", "—", false, true, 72),
                stage("CAD", "Documentation & sanction letter", "—", false, true, 24),
                stage("BOOKING", "Limit booking", "—", false, false, 4),
                stage("MONITORING", "Post-disbursement monitoring", "—", false, false, 8760));
        return new WorkflowDefinitionDto("fallback_workflow", 0,
                segment == null || segment.isEmpty() ? "DEFAULT" : segment, stages);
    }

    private static Map<String, Object> stage(String key, String label, String autonomy,
                                              boolean ai, boolean humanGate, int slaHours) {
        return Map.of("key", key, "label", label, "autonomy", autonomy,
                "ai", ai, "humanGate", humanGate, "slaHours", slaHours);
    }
}
