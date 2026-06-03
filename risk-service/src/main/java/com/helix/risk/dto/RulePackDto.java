package com.helix.risk.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.Map;

/** Lightweight view of a config-service rule pack (loose coupling across services). */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RulePackDto(Long id, String code, String type, String jurisdiction, int version,
                          Map<String, Object> payload) {

    public double number(String key, double fallback) {
        Object v = payload == null ? null : payload.get(key);
        return v instanceof Number n ? n.doubleValue() : fallback;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> map(String key) {
        Object v = payload == null ? null : payload.get(key);
        return v instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }
}
