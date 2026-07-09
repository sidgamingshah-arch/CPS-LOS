package com.helix.counterparty.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

/**
 * Reads master data from config-service (dedup rules, negative list, thresholds).
 * Degrades gracefully to conservative built-ins if config-service is unavailable.
 */
@Component
public class ConfigMasterClient {

    private static final Logger log = LoggerFactory.getLogger(ConfigMasterClient.class);

    private final RestClient client;

    public ConfigMasterClient(@Value("${helix.config-service.base-url}") String baseUrl) {
        this.client = RestClient.builder().baseUrl(baseUrl).build();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record MasterRecordDto(Long id, String masterType, String recordKey, String jurisdiction,
                                  Map<String, Object> payload, String status, int version) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RulePackDto(String code, int version, Map<String, Object> payload) {
    }

    /**
     * Re-KYC interval (months) for a jurisdiction + CDD tier, read from the CDD_TIERS rule pack
     * ({@code payload.rekyc_months.<TIER>} — E3). Degrades to the conservative RBI/CBUAE default
     * (ENHANCED 12 / STANDARD 24 / SIMPLIFIED 36) if config-service is unreachable or the key is
     * absent, so the sweep is deterministic regardless of config reachability.
     */
    /**
     * The CDD_TIERS rule-pack payload for a jurisdiction (E3): {@code enhanced_triggers},
     * {@code simplified_eligible}, {@code default_tier}, {@code rekyc_months}, … Degrades to the
     * built-in RBI/CBUAE tiering (triggers matching the historical hardcoded logic) if
     * config-service is unreachable, so tiering is deterministic regardless of reachability.
     */
    public Map<String, Object> cddTiers(String jurisdiction) {
        try {
            RulePackDto pack = client.get().uri(uri -> uri.path("/api/rulepacks")
                            .queryParam("jurisdiction", jurisdiction)
                            .queryParam("type", "CDD_TIERS").build())
                    .retrieve().body(RulePackDto.class);
            if (pack != null && pack.payload() != null && !pack.payload().isEmpty()) {
                return pack.payload();
            }
        } catch (Exception e) {
            log.warn("config-service CDD_TIERS unavailable for {} ({}); using built-in tiering",
                    jurisdiction, e.getMessage());
        }
        return Map.of(
                "enhanced_triggers", List.of("PEP", "HIGH_RISK_JURISDICTION", "ADVERSE_MEDIA", "COMPLEX_OWNERSHIP"),
                "simplified_eligible", List.of("LISTED_ENTITY", "REGULATED_FI"),
                "default_tier", "STANDARD");
    }

    @SuppressWarnings("unchecked")
    public int reKycMonths(String jurisdiction, String tier) {
        int fallback = switch (tier == null ? "" : tier.toUpperCase()) {
            case "ENHANCED" -> 12;
            case "SIMPLIFIED" -> 36;
            default -> 24;   // STANDARD
        };
        try {
            RulePackDto pack = client.get().uri(uri -> uri.path("/api/rulepacks")
                            .queryParam("jurisdiction", jurisdiction)
                            .queryParam("type", "CDD_TIERS").build())
                    .retrieve().body(RulePackDto.class);
            if (pack == null || pack.payload() == null) return fallback;
            Object rk = pack.payload().get("rekyc_months");
            if (rk instanceof Map<?, ?> m) {
                Object v = ((Map<String, Object>) m).get(tier == null ? "" : tier.toUpperCase());
                if (v instanceof Number n) return n.intValue();
            }
            return fallback;
        } catch (Exception e) {
            log.warn("config-service CDD_TIERS unavailable for {} ({}); using fallback {}mo",
                    jurisdiction, e.getMessage(), fallback);
            return fallback;
        }
    }

    @SuppressWarnings("unchecked")
    public List<MasterRecordDto> listActive(String type) {
        try {
            MasterRecordDto[] arr = client.get().uri("/api/masters/{t}", type).retrieve().body(MasterRecordDto[].class);
            return arr == null ? List.of() : List.of(arr);
        } catch (Exception e) {
            log.warn("config-service master {} unavailable ({})", type, e.getMessage());
            return List.of();
        }
    }

    public Map<String, Object> dedupRules() {
        return listActive("DEDUP_RULES").stream().findFirst()
                .map(MasterRecordDto::payload)
                .orElse(Map.of("strategy", "NAME_AND_IDENTIFIER",
                        "identifierFields", List.of("registrationNo"), "nameMatchThreshold", 0.82));
    }

    public List<MasterRecordDto> negativeList() {
        return listActive("NEGATIVE_LIST");
    }

    public int draftCleanupMonths() {
        return listActive("DRAFT_CLEANUP").stream().findFirst()
                .map(r -> ((Number) r.payload().getOrDefault("months", 6)).intValue())
                .orElse(6);
    }

    public int inactivityDays() {
        return listActive("INACTIVITY_THRESHOLD").stream().findFirst()
                .map(r -> ((Number) r.payload().getOrDefault("days", 90)).intValue())
                .orElse(90);
    }
}
