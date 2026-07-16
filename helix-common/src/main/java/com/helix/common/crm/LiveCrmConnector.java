package com.helix.common.crm;

import com.helix.common.export.Export;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Live {@link CrmConnector}: performs a real {@code RestClient} POST of the canonical case-status
 * envelope to the configured CRM. Active only when {@code helix.crm.mode=live}. Config:
 * {@code helix.crm.base-url} (required), {@code helix.crm.path} (default {@code /cases/status}),
 * {@code helix.crm.auth-header}/{@code helix.crm.auth-token} (optional static auth). Never throws —
 * any failure is captured as {@link Result#failed(String)} so the write-back row records the outcome
 * without breaking the business operation.
 */
@Component
@ConditionalOnProperty(name = "helix.crm.mode", havingValue = "live")
public class LiveCrmConnector implements CrmConnector {

    private static final Logger log = LoggerFactory.getLogger(LiveCrmConnector.class);

    private final RestClient http;
    private final String path;
    private final String authHeader;
    private final String authToken;

    public LiveCrmConnector(@Value("${helix.crm.base-url:}") String baseUrl,
                            @Value("${helix.crm.path:/cases/status}") String path,
                            @Value("${helix.crm.auth-header:}") String authHeader,
                            @Value("${helix.crm.auth-token:}") String authToken) {
        this.http = RestClient.builder().baseUrl(baseUrl).build();
        this.path = path;
        this.authHeader = authHeader;
        this.authToken = authToken;
        if (baseUrl.isBlank()) {
            log.warn("CRM live mode enabled but helix.crm.base-url is not set — write-backs will fail");
        } else {
            log.info("CRM live connector -> {}{}", baseUrl, path);
        }
    }

    @Override
    public String mode() {
        return "LIVE";
    }

    @Override
    public Result push(Export.Envelope<Export.CrmCaseStatusRecord> envelope) {
        try {
            var resp = http.post().uri(path)
                    .contentType(MediaType.APPLICATION_JSON)
                    .headers(h -> {
                        if (!authHeader.isBlank() && !authToken.isBlank()) h.set(authHeader, authToken);
                    })
                    .body(envelope)
                    .retrieve()
                    .toBodilessEntity();
            String providerRef = resp.getHeaders().getFirst("X-Crm-Ref");
            return Result.delivered(providerRef != null ? providerRef
                    : "crm:" + resp.getStatusCode().value() + ":" + envelope.idempotencyKey());
        } catch (Exception e) {
            log.warn("CRM write-back POST failed for {} ({})", envelope.idempotencyKey(), e.getMessage());
            return Result.failed(e.getMessage());
        }
    }
}
