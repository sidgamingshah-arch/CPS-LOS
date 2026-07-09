package com.helix.portfolio.reports;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.helix.common.web.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;

/**
 * Loads a saved {@code REPORT_DEFINITION} master from config-service by
 * recordKey, and converts its payload into a {@link ReportDefinition}. The
 * master is human-authored under maker-checker — same engine as every other
 * master in the platform, so versioning + SoD come for free.
 */
@Component
public class ReportDefinitionClient {

    private static final Logger log = LoggerFactory.getLogger(ReportDefinitionClient.class);

    private final RestClient config;
    private final ObjectMapper mapper = new ObjectMapper();

    public ReportDefinitionClient(@Value("${helix.config-service.base-url}") String baseUrl) {
        this.config = RestClient.builder().baseUrl(baseUrl).build();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record MasterRow(Long id, String masterType, String recordKey, String status,
                            Map<String, Object> payload) {
    }

    public ReportDefinition load(String reportKey) {
        try {
            MasterRow[] all = config.get().uri("/api/masters/REPORT_DEFINITION")
                    .retrieve().body(MasterRow[].class);
            if (all == null) throw ApiException.notFound("No REPORT_DEFINITION masters found");
            for (MasterRow r : all) {
                if (reportKey.equalsIgnoreCase(r.recordKey()) && "ACTIVE".equals(r.status())) {
                    return mapper.convertValue(r.payload(), ReportDefinition.class);
                }
            }
            throw ApiException.notFound("No ACTIVE REPORT_DEFINITION with key " + reportKey);
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Could not load REPORT_DEFINITION {} ({})", reportKey, e.getMessage());
            throw ApiException.notFound("REPORT_DEFINITION " + reportKey + " unavailable");
        }
    }
}
