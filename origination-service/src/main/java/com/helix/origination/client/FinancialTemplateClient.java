package com.helix.origination.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Resolves the active FINANCIAL_TEMPLATE for a deal (jurisdiction, segment) from
 * config-service and parses it into a typed view. The template AUGMENTS the
 * canonical chart with extra input lines, derived lines, and ratios (formulas).
 * Degrades to {@link FinancialTemplate#EMPTY} when none resolves / config is
 * unreachable — so spreading always works on the canonical chart alone.
 */
@Component
public class FinancialTemplateClient {

    private static final Logger log = LoggerFactory.getLogger(FinancialTemplateClient.class);

    private final RestClient config;

    public FinancialTemplateClient(@Value("${helix.config-service.base-url}") String baseUrl) {
        this.config = RestClient.builder().baseUrl(baseUrl).build();
    }

    public record Line(String key, String label) { }

    public record Formula(String key, String label, String formula) { }

    public record FinancialTemplate(String templateKey, List<Line> extraInputs,
                                    List<Formula> extraDerived, List<Formula> extraRatios,
                                    /** Extraction-field-name (lower-cased) → canonical taxonomy key.
                                     *  Drives the doc-intel → spread bridge so "extraction happens
                                     *  against the template master". Empty → the consumer's built-in
                                     *  default map applies (byte-identical to today). */
                                    Map<String, String> extractionMap) {
        public static final FinancialTemplate EMPTY =
                new FinancialTemplate("(none)", List.of(), List.of(), List.of(), Map.of());

        public boolean hasExtras() {
            return !extraInputs.isEmpty() || !extraDerived.isEmpty() || !extraRatios.isEmpty();
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ResolveDto(String recordKey, Map<String, Object> payload) { }

    public FinancialTemplate resolve(String jurisdiction, String sector, String segment) {
        try {
            ResolveDto dto = config.get().uri(uri -> uri.path("/api/financial-templates/resolve")
                            .queryParam("jurisdiction", jurisdiction == null ? "" : jurisdiction)
                            .queryParam("sector", sector == null ? "" : sector)
                            .queryParam("segment", segment == null ? "" : segment)
                            .build())
                    .retrieve().body(ResolveDto.class);
            if (dto == null || dto.payload() == null) return FinancialTemplate.EMPTY;
            return parse(dto.payload());
        } catch (Exception e) {
            log.warn("FINANCIAL_TEMPLATE resolve unavailable for {}/{}/{} ({}) — canonical chart only",
                    jurisdiction, sector, segment, e.getMessage());
            return FinancialTemplate.EMPTY;
        }
    }

    @SuppressWarnings("unchecked")
    private FinancialTemplate parse(Map<String, Object> p) {
        String key = String.valueOf(p.getOrDefault("templateKey", "(none)"));
        List<Line> inputs = new ArrayList<>();
        if (p.get("extraInputLines") instanceof List<?> l) {
            for (Object o : l) if (o instanceof Map<?, ?> m) {
                inputs.add(new Line(str(m.get("key")), str(m.get("label"))));
            }
        }
        return new FinancialTemplate(key, inputs, formulas(p.get("extraDerivedLines")),
                formulas(p.get("extraRatios")), extractionMap(p.get("extractionMap")));
    }

    /** Parses {@code payload.extractionMap} ({extractionFieldName → canonicalKey}); keys are
     *  lower-cased to match the consumer's lookup. Non-map / null → an empty map (built-in default). */
    private Map<String, String> extractionMap(Object raw) {
        Map<String, String> out = new LinkedHashMap<>();
        if (raw instanceof Map<?, ?> m) {
            for (Map.Entry<?, ?> en : m.entrySet()) {
                if (en.getKey() == null || en.getValue() == null) continue;
                out.put(String.valueOf(en.getKey()).toLowerCase(Locale.ROOT), String.valueOf(en.getValue()));
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private List<Formula> formulas(Object raw) {
        List<Formula> out = new ArrayList<>();
        if (raw instanceof List<?> l) {
            for (Object o : l) if (o instanceof Map<?, ?> m) {
                out.add(new Formula(str(m.get("key")), str(m.get("label")), str(m.get("formula"))));
            }
        }
        return out;
    }

    private static String str(Object o) { return o == null ? null : String.valueOf(o); }
}
