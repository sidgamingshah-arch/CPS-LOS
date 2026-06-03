package com.helix.common.util;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import java.util.List;
import java.util.Map;

/**
 * SQLite has no native JSON column type, so structured fields (score breakdowns,
 * reasons, conditions, provenance) are persisted as TEXT and (de)serialized here.
 */
public final class JsonAttributeConverters {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private JsonAttributeConverters() {
    }

    @Converter
    public static class MapConverter implements AttributeConverter<Map<String, Object>, String> {
        @Override
        public String convertToDatabaseColumn(Map<String, Object> attribute) {
            try {
                return attribute == null ? "{}" : MAPPER.writeValueAsString(attribute);
            } catch (Exception e) {
                throw new IllegalStateException("Cannot serialize map to JSON", e);
            }
        }

        @Override
        public Map<String, Object> convertToEntityAttribute(String dbData) {
            try {
                if (dbData == null || dbData.isBlank()) {
                    return Map.of();
                }
                return MAPPER.readValue(dbData, new TypeReference<>() {
                });
            } catch (Exception e) {
                throw new IllegalStateException("Cannot deserialize JSON to map", e);
            }
        }
    }

    @Converter
    public static class StringListConverter implements AttributeConverter<List<String>, String> {
        @Override
        public String convertToDatabaseColumn(List<String> attribute) {
            try {
                return attribute == null ? "[]" : MAPPER.writeValueAsString(attribute);
            } catch (Exception e) {
                throw new IllegalStateException("Cannot serialize list to JSON", e);
            }
        }

        @Override
        public List<String> convertToEntityAttribute(String dbData) {
            try {
                if (dbData == null || dbData.isBlank()) {
                    return List.of();
                }
                return MAPPER.readValue(dbData, new TypeReference<>() {
                });
            } catch (Exception e) {
                throw new IllegalStateException("Cannot deserialize JSON to list", e);
            }
        }
    }
}
