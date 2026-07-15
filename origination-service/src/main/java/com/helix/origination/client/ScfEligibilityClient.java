package com.helix.origination.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

/**
 * Reads the {@code SCF_ELIGIBILITY} master from config-service and resolves the eligibility
 * criteria for a programme (keyed by {@code programType}, falling back to a {@code DEFAULT}
 * record, then to conservative built-ins when the master is absent or config-service is
 * unreachable). The resolved map is pinned onto the {@link com.helix.origination.entity.ScfProgram}
 * at create time so every spoke is judged deterministically against the same snapshot.
 */
@Component
public class ScfEligibilityClient {

    private static final Logger log = LoggerFactory.getLogger(ScfEligibilityClient.class);

    private final RestClient client;

    public ScfEligibilityClient(@Value("${helix.config-service.base-url:http://localhost:8081}") String baseUrl) {
        this.client = RestClient.builder().baseUrl(baseUrl).build();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record MasterRecordDto(Long id, String masterType, String recordKey, String jurisdiction,
                                  Map<String, Object> payload, String status, int version) {
    }

    /**
     * Conservative built-in criteria used when config-service is unavailable or no
     * SCF_ELIGIBILITY row exists: bounded caps that still let a spoke be judged, tighter
     * than an unbounded pass (a 25%-of-programme ceiling, both programme types allowed).
     */
    public static Map<String, Object> builtInDefaults() {
        return Map.of(
                "minSpokeAmount", 0.0,
                "maxSpokeAmount", 100_000_000.0,
                "maxSpokePctOfProgram", 25.0,
                "allowedProgramTypes", List.of("VENDOR", "DEALER"),
                "source", "BUILT_IN_FALLBACK");
    }

    /** Resolve criteria for a programme type: exact record → DEFAULT record → built-in fallback. */
    public Map<String, Object> resolve(String programType) {
        List<MasterRecordDto> all = listActive();
        MasterRecordDto exact = null, def = null;
        for (MasterRecordDto m : all) {
            String key = m.recordKey();
            if (programType != null && programType.equalsIgnoreCase(key)) exact = m;
            else if ("DEFAULT".equalsIgnoreCase(key)) def = m;
        }
        MasterRecordDto pick = exact != null ? exact : def;
        if (pick != null && pick.payload() != null && !pick.payload().isEmpty()) {
            return pick.payload();
        }
        return builtInDefaults();
    }

    private List<MasterRecordDto> listActive() {
        try {
            MasterRecordDto[] arr = client.get().uri("/api/masters/{t}", "SCF_ELIGIBILITY")
                    .retrieve().body(MasterRecordDto[].class);
            return arr == null ? List.of() : List.of(arr);
        } catch (Exception e) {
            log.warn("config-service SCF_ELIGIBILITY master unavailable ({}); using built-in defaults", e.getMessage());
            return List.of();
        }
    }
}
