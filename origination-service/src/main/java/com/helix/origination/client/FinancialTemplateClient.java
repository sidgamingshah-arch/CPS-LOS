package com.helix.origination.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;
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
                                    List<Formula> extraDerived, List<Formula> extraRatios) {
        public static final FinancialTemplate EMPTY =
                new FinancialTemplate("(none)", List.of(), List.of(), List.of());

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
                formulas(p.get("extraRatios")));
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
