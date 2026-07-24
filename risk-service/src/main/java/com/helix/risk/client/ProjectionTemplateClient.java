package com.helix.risk.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.helix.common.web.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Resolves + parses the active PROJECTION_TEMPLATE for a deal from config-service.
 * The template is a driver model: each line formula references a driver, the
 * base-year actual ({@code base_<LINE>}), the prior projected year
 * ({@code prev_<LINE>}), or another current-year line ({@code <LINE>}).
 */
@Component
public class ProjectionTemplateClient {

    private static final Logger log = LoggerFactory.getLogger(ProjectionTemplateClient.class);

    private final RestClient config;

    public ProjectionTemplateClient(@Value("${helix.config-service.base-url}") String baseUrl) {
        this.config = RestClient.builder().baseUrl(baseUrl).build();
    }

    /**
     * A driver assumption. {@code volatility} is the fractional standard deviation used by the
     * Monte-Carlo simulation (σ = |mean| × volatility), calibrated from industry / peer / historical
     * inputs; absent in the template → a conservative default. Inert for the deterministic proforma.
     */
    public record Driver(String key, String label, double defaultValue, double volatility) { }

    public record Line(String key, String label, String formula, String seedFrom) { }

    public record Template(String templateKey, int version, int horizonYears,
                           List<Driver> drivers, List<Line> lines) { }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ResolveDto(String recordKey, int version, Map<String, Object> payload) { }

    public Template resolve(String jurisdiction, String sector, String segment) {
        try {
            ResolveDto dto = config.get().uri(uri -> uri.path("/api/projection-templates/resolve")
                            .queryParam("jurisdiction", jurisdiction == null ? "" : jurisdiction)
                            .queryParam("sector", sector == null ? "" : sector)
                            .queryParam("segment", segment == null ? "" : segment)
                            .build())
                    .retrieve().body(ResolveDto.class);
            if (dto == null || dto.payload() == null) {
                throw ApiException.conflict("No projection template configured for " + jurisdiction + "/" + segment);
            }
            return parse(dto.version(), dto.payload());
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            log.warn("PROJECTION_TEMPLATE resolve failed for {}/{}/{} ({})", jurisdiction, sector, segment, e.getMessage());
            throw ApiException.conflict("No projection template available: " + e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private Template parse(int version, Map<String, Object> p) {
        String key = String.valueOf(p.getOrDefault("templateKey", "(none)"));
        int horizon = p.get("horizonYears") instanceof Number n ? n.intValue() : 5;
        List<Driver> drivers = new ArrayList<>();
        if (p.get("drivers") instanceof List<?> l) {
            for (Object o : l) if (o instanceof Map<?, ?> m) {
                Object dv = m.get("defaultValue");
                Object vol = m.get("volatility");
                drivers.add(new Driver(str(m.get("key")), str(m.get("label")),
                        dv instanceof Number n ? n.doubleValue() : 0.0,
                        vol instanceof Number vn ? vn.doubleValue() : 0.15));
            }
        }
        List<Line> lines = new ArrayList<>();
        if (p.get("lines") instanceof List<?> l) {
            for (Object o : l) if (o instanceof Map<?, ?> m) {
                lines.add(new Line(str(m.get("key")), str(m.get("label")),
                        str(m.get("formula")), m.get("seedFrom") == null ? null : str(m.get("seedFrom"))));
            }
        }
        return new Template(key, version, horizon, drivers, lines);
    }

    /** The template defaults as a plain map (driver key -> value). */
    public static Map<String, Double> defaults(Template t) {
        Map<String, Double> m = new LinkedHashMap<>();
        for (Driver d : t.drivers()) m.put(d.key(), d.defaultValue());
        return m;
    }

    private static String str(Object o) { return o == null ? null : String.valueOf(o); }
}
