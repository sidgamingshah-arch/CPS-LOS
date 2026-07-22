package com.helix.portfolio.entity;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import java.util.List;
import java.util.Map;

/**
 * Persists the per-member cash-flow contribution list as JSON TEXT (SQLite has no native
 * JSON type). Each element is a small {@code {ref, name, revenue, ebitda, cfo, debtService,
 * dscr}} map — deterministic figures only. Not {@code autoApply}ed; wired explicitly on the
 * {@link GlobalCashflowAssessment#getMembers()} column so no other list field is affected.
 */
@Converter
public class MemberContributionListConverter
        implements AttributeConverter<List<Map<String, Object>>, String> {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public String convertToDatabaseColumn(List<Map<String, Object>> attribute) {
        try {
            return attribute == null ? "[]" : MAPPER.writeValueAsString(attribute);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot serialize member contributions to JSON", e);
        }
    }

    @Override
    public List<Map<String, Object>> convertToEntityAttribute(String dbData) {
        try {
            if (dbData == null || dbData.isBlank()) {
                return List.of();
            }
            return MAPPER.readValue(dbData, new TypeReference<>() {
            });
        } catch (Exception e) {
            throw new IllegalStateException("Cannot deserialize member contributions JSON", e);
        }
    }
}
