package com.helix.risk.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.helix.common.web.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;

/**
 * Resolves the active scoring-model definition for a deal's
 * (jurisdiction, sector, segment) from config-service. The definition itself is
 * a governed {@code MODEL_DEFINITION} master; this just asks config to pick the
 * most-specific match. Surfaces 404 when nothing matches (no silent fallback —
 * a model must be configured to score).
 */
@Component
public class ModelDefinitionClient {

    private static final Logger log = LoggerFactory.getLogger(ModelDefinitionClient.class);

    private final RestClient config;

    public ModelDefinitionClient(@Value("${helix.config-service.base-url}") String baseUrl) {
        this.config = RestClient.builder().baseUrl(baseUrl).build();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ResolvedModel(Long id, String recordKey, int version, Map<String, Object> payload) {
    }

    public ResolvedModel resolve(String jurisdiction, String sector, String segment) {
        try {
            return config.get().uri(uri -> uri.path("/api/models/resolve")
                            .queryParam("jurisdiction", jurisdiction == null ? "" : jurisdiction)
                            .queryParam("sector", sector == null ? "" : sector)
                            .queryParam("segment", segment == null ? "" : segment)
                            .build())
                    .retrieve().body(ResolvedModel.class);
        } catch (org.springframework.web.client.HttpClientErrorException.NotFound nf) {
            throw ApiException.conflict("No scoring model configured for jurisdiction=" + jurisdiction
                    + " sector=" + sector + " segment=" + segment + " — configure a MODEL_DEFINITION first");
        } catch (Exception e) {
            log.warn("config-service model resolve failed ({})", e.getMessage());
            throw new ApiException(HttpStatus.BAD_GATEWAY, "config-service unavailable for model resolve: " + e.getMessage());
        }
    }
}
