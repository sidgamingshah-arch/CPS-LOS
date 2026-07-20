package com.helix.counterparty.service;

import com.helix.common.ingest.Ingestion.Envelope;
import com.helix.common.ingest.SourceSystem;
import com.helix.common.web.ApiException;
import com.helix.counterparty.dto.IngestDtos.RawCrmPayload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.CRC32;

/**
 * Inbound CRM PULL source (PRD §8), config-gated exactly like the CRM write-back simulated|live
 * gating. NOTE: the INBOUND fetch uses {@code helix.crm.fetch.base-url} so it never collides with
 * the OUTBOUND write-back's {@code helix.crm.base-url}.
 *
 * <ul>
 *   <li><b>Simulated (DEFAULT)</b> — {@code helix.crm.fetch.base-url} is BLANK. Returns a
 *       deterministic sample profile; vendor marked {@code simulated}; NO network call is made.</li>
 *   <li><b>Live</b> — set. Real {@code RestClient} GET, mapped to the raw payload; failure surfaces
 *       fail-soft as a clear {@code BAD_GATEWAY}/{@code BAD_REQUEST}, never a raw 500 stack.</li>
 * </ul>
 */
@Component
public class CrmFetchSource {

    private static final Logger log = LoggerFactory.getLogger(CrmFetchSource.class);

    private final String baseUrl;
    private final String path;
    private final String liveVendor;
    private final String payloadVersion;
    private final RestClient http;

    public CrmFetchSource(@Value("${helix.crm.fetch.base-url:}") String baseUrl,
                          @Value("${helix.crm.fetch.path:/crm/profile}") String path,
                          @Value("${helix.crm.fetch.vendor:CRM}") String liveVendor,
                          @Value("${helix.crm.fetch.payload-version:1.0}") String payloadVersion) {
        this.baseUrl = baseUrl == null ? "" : baseUrl.trim();
        this.path = path;
        this.liveVendor = liveVendor;
        this.payloadVersion = payloadVersion;
        this.http = this.baseUrl.isBlank() ? null : RestClient.builder().baseUrl(this.baseUrl).build();
        if (this.baseUrl.isBlank()) {
            log.info("CRM fetch source in SIMULATED mode (helix.crm.fetch.base-url unset) — no egress");
        } else {
            log.info("CRM fetch source LIVE -> {}{}", this.baseUrl, path);
        }
    }

    public boolean live() {
        return !baseUrl.isBlank();
    }

    /** Fetch a CRM profile for the given subject, wrapped in a canonical {@link Envelope}. */
    public Envelope<RawCrmPayload> fetch(String accountName, String subjectId) {
        if (!live()) {
            return simulated(accountName, subjectId);
        }
        try {
            RawCrmPayload raw = http.get()
                    .uri(uri -> uri.path(path)
                            .queryParam("accountName", accountName == null ? "" : accountName)
                            .queryParam("subjectId", subjectId == null ? "" : subjectId)
                            .build())
                    .retrieve()
                    .body(RawCrmPayload.class);
            if (raw == null) {
                throw new ApiException(HttpStatus.BAD_GATEWAY,
                        "CRM live fetch returned an empty payload for " + subjectId);
            }
            String key = "CRM-LIVE-" + stableToken(accountName, subjectId);
            return new Envelope<>(SourceSystem.CRM, liveVendor, key, payloadVersion, raw);
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            log.warn("CRM live fetch failed for {} ({})", subjectId, e.getMessage());
            throw new ApiException(HttpStatus.BAD_GATEWAY,
                    "CRM live fetch failed for " + subjectId + ": " + e.getMessage());
        }
    }

    /** Deterministic simulated profile — same subject always yields the same figures. */
    private Envelope<RawCrmPayload> simulated(String accountName, String subjectId) {
        long token = stableToken(accountName, subjectId);
        String[] segments = {"CORPORATE", "SME", "MID_MARKET", "FI"};
        String[] stages = {"PROSPECT", "ONBOARDING", "ACTIVE", "RENEWAL"};
        String seg = segments[(int) (token % segments.length)];
        String stage = stages[(int) ((token / segments.length) % stages.length)];
        double relationshipValue = 5_000_000d + (token % 400) * 25_000d;
        RawCrmPayload raw = new RawCrmPayload(
                "CRM-" + token,
                accountName == null || accountName.isBlank() ? "ACCOUNT-" + subjectId : accountName,
                "rm.simulated",
                seg,
                relationshipValue,
                "Contact " + (token % 100),
                "contact" + (token % 100) + "@example.com",
                List.of("WORKING_CAPITAL", "TERM_LOAN"),
                stage);
        String key = "CRM-SIM-" + token;
        return new Envelope<>(SourceSystem.CRM, "simulated-crm", key, "sim-1.0", raw);
    }

    private long stableToken(String accountName, String subjectId) {
        CRC32 crc = new CRC32();
        crc.update(((accountName == null ? "" : accountName) + "|" + (subjectId == null ? "" : subjectId))
                .getBytes(StandardCharsets.UTF_8));
        return crc.getValue();
    }
}
