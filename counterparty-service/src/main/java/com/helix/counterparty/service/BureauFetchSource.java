package com.helix.counterparty.service;

import com.helix.common.ingest.Ingestion.Envelope;
import com.helix.common.ingest.SourceSystem;
import com.helix.common.web.ApiException;
import com.helix.counterparty.dto.IngestDtos.RawBureauPayload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;

/**
 * Inbound credit-bureau PULL source (PRD §8), config-gated exactly like the CRM write-back
 * simulated|live gating:
 *
 * <ul>
 *   <li><b>Simulated (DEFAULT)</b> — {@code helix.bureau.base-url} is BLANK. Returns a
 *       deterministic sample raw payload for the subject; the vendor is marked {@code simulated}
 *       and NO network call is ever made, so the fetch path is demoable with no external system.</li>
 *   <li><b>Live</b> — {@code helix.bureau.base-url} set. Performs a real {@code RestClient} GET
 *       and maps the response to the raw payload. Any failure is surfaced fail-soft as a clear
 *       {@code BAD_GATEWAY}/{@code BAD_REQUEST} {@link ApiException}, never a raw 500 stack.</li>
 * </ul>
 *
 * This produces the {@link Envelope} only; idempotency + persistence live in the ingestion service.
 */
@Component
public class BureauFetchSource {

    private static final Logger log = LoggerFactory.getLogger(BureauFetchSource.class);

    private final String baseUrl;
    private final String path;
    private final String liveVendor;
    private final String payloadVersion;
    private final RestClient http;

    public BureauFetchSource(@Value("${helix.bureau.base-url:}") String baseUrl,
                             @Value("${helix.bureau.path:/bureau/report}") String path,
                             @Value("${helix.bureau.vendor:CREDIT_BUREAU}") String liveVendor,
                             @Value("${helix.bureau.payload-version:1.0}") String payloadVersion) {
        this.baseUrl = baseUrl == null ? "" : baseUrl.trim();
        this.path = path;
        this.liveVendor = liveVendor;
        this.payloadVersion = payloadVersion;
        this.http = this.baseUrl.isBlank() ? null : RestClient.builder().baseUrl(this.baseUrl).build();
        if (this.baseUrl.isBlank()) {
            log.info("Bureau fetch source in SIMULATED mode (helix.bureau.base-url unset) — no egress");
        } else {
            log.info("Bureau fetch source LIVE -> {}{}", this.baseUrl, path);
        }
    }

    public boolean live() {
        return !baseUrl.isBlank();
    }

    /**
     * Fetch a bureau report for the given subject, wrapped in a canonical {@link Envelope}.
     * Simulated mode is deterministic and never calls out; live mode is fail-soft.
     */
    public Envelope<RawBureauPayload> fetch(String subjectName, String subjectId) {
        if (!live()) {
            return simulated(subjectName, subjectId);
        }
        try {
            RawBureauPayload raw = http.get()
                    .uri(uri -> uri.path(path)
                            .queryParam("subjectName", subjectName == null ? "" : subjectName)
                            .queryParam("subjectId", subjectId == null ? "" : subjectId)
                            .build())
                    .retrieve()
                    .body(RawBureauPayload.class);
            if (raw == null) {
                throw new ApiException(HttpStatus.BAD_GATEWAY,
                        "Bureau live fetch returned an empty payload for " + subjectId);
            }
            String key = "BUREAU-LIVE-" + stableToken(subjectName, subjectId);
            return new Envelope<>(SourceSystem.CREDIT_BUREAU, liveVendor, key, payloadVersion, raw);
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Bureau live fetch failed for {} ({})", subjectId, e.getMessage());
            throw new ApiException(HttpStatus.BAD_GATEWAY,
                    "Bureau live fetch failed for " + subjectId + ": " + e.getMessage());
        }
    }

    /** Deterministic simulated payload — same subject always yields the same figures. */
    private Envelope<RawBureauPayload> simulated(String subjectName, String subjectId) {
        long token = stableToken(subjectName, subjectId);
        int score = 650 + (int) (token % 200);            // 650..849, deterministic
        int enquiries = (int) (token % 5);                 // 0..4
        int delinquencies = (int) ((token / 5) % 3);       // 0..2
        int tradelines = 4 + (int) (token % 9);            // 4..12
        double outstanding = 1_000_000d + (token % 500) * 10_000d;
        int oldestMonths = 24 + (int) (token % 120);       // 24..143
        RawBureauPayload raw = new RawBureauPayload(
                "simulated-bureau",
                subjectName == null || subjectName.isBlank() ? "SUBJECT-" + subjectId : subjectName,
                subjectId,
                score, "SIM-SCORE-V1",
                enquiries, delinquencies, tradelines, outstanding, oldestMonths);
        String key = "BUREAU-SIM-" + token;
        return new Envelope<>(SourceSystem.CREDIT_BUREAU, "simulated-bureau", key, "sim-1.0", raw);
    }

    private long stableToken(String subjectName, String subjectId) {
        CRC32 crc = new CRC32();
        crc.update(((subjectName == null ? "" : subjectName) + "|" + (subjectId == null ? "" : subjectId))
                .getBytes(StandardCharsets.UTF_8));
        return crc.getValue();
    }
}
