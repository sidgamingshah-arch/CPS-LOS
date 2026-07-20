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
import java.util.ArrayList;
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
    private final String createPath;
    private final String listPath;
    private final String liveVendor;
    private final String payloadVersion;
    private final RestClient http;

    public CrmFetchSource(@Value("${helix.crm.fetch.base-url:}") String baseUrl,
                          @Value("${helix.crm.fetch.path:/crm/profile}") String path,
                          @Value("${helix.crm.fetch.create-path:/crm/borrower}") String createPath,
                          @Value("${helix.crm.fetch.list-path:/crm/borrowers}") String listPath,
                          @Value("${helix.crm.fetch.vendor:CRM}") String liveVendor,
                          @Value("${helix.crm.fetch.payload-version:1.0}") String payloadVersion) {
        this.baseUrl = baseUrl == null ? "" : baseUrl.trim();
        this.path = path;
        this.createPath = createPath;
        this.listPath = listPath;
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
                stage,
                // identity block unused by the ENRICH path — left null (byte-identical to before)
                null, null, null, null, null, null, null, null, null);
        String key = "CRM-SIM-" + token;
        return new Envelope<>(SourceSystem.CRM, "simulated-crm", key, "sim-1.0", raw);
    }

    // ==================================================================================
    // Pull-and-CREATE fetch (governed obligor creation). Distinct from the enrich fetch:
    // it carries the identity block so CrmObligorPullService can map it onto a
    // CreateProspectRequest and route creation through the governed initiation flow.
    // ==================================================================================

    /**
     * Fetch a single CRM borrower for governed CREATION, keyed by CRM id. Simulated by default
     * (deterministic, no egress); live via {@code helix.crm.fetch.create-path}. Fail-soft.
     */
    public Envelope<RawCrmPayload> fetchForCreate(String crmId) {
        if (crmId == null || crmId.isBlank()) {
            throw ApiException.badRequest("crmId is required to pull a borrower from CRM");
        }
        return live() ? liveBorrower(crmId.trim()) : simulatedBorrower(crmId.trim());
    }

    /**
     * Batch fetch borrowers for governed CREATION. When {@code crmIds} are supplied each is
     * fetched individually; when null/empty the source returns its default sample list
     * (simulated) or the CRM list endpoint (live) — so a batch pull is demoable with no body.
     */
    public List<Envelope<RawCrmPayload>> fetchBatchForCreate(List<String> crmIds) {
        if (crmIds != null && !crmIds.isEmpty()) {
            List<Envelope<RawCrmPayload>> out = new ArrayList<>();
            for (String id : crmIds) {
                out.add(fetchForCreate(id));
            }
            return out;
        }
        return live() ? liveBatch() : simulatedBatch();
    }

    /**
     * Deterministic simulated borrower — same crmId always yields the same identity, so the
     * pull is reproducible with no external CRM. The crmId is echoed back (a stable idempotency
     * anchor) and embedded in a traceable {@code registrationNo} so dedup against an
     * already-onboarded counterparty can be exercised. A crmId containing {@code NEG} yields a
     * sanctioned-country borrower (negative-list demo). Statutory identifiers (pan/gstin/lei/cin)
     * are left null (they are format-validated + optional on creation).
     */
    private Envelope<RawCrmPayload> simulatedBorrower(String crmId) {
        long token = stableToken(crmId, "create");
        String[] segments = {"CORPORATE", "SME", "MID_MARKET", "FI"};
        String seg = segments[(int) (token % segments.length)];
        boolean sanctioned = crmId.toUpperCase().contains("NEG");
        String jurisdiction = (token % 2 == 0) ? "IN-RBI" : "AE-CBUAE";
        String country = sanctioned ? "CU" : ("AE-CBUAE".equals(jurisdiction) ? "AE" : "IN");
        RawCrmPayload raw = new RawCrmPayload(
                crmId,                                     // crmId echoed (stable idempotency anchor)
                "Borrower " + crmId,                       // accountName
                "rm.simulated",
                seg,
                5_000_000d + (token % 400) * 25_000d,
                "Contact " + (token % 100),
                "contact" + (token % 100) + "@example.com",
                List.of("WORKING_CAPITAL", "TERM_LOAN"),
                "PROSPECT",
                // ---- identity block for governed creation ----
                "Borrower " + crmId,                       // legalName
                "NTB",                                     // borrowerType
                jurisdiction,
                country,
                "CRMREG-" + crmId,                         // registrationNo (traceable; drives dedup)
                null, null, null, null);                   // pan/gstin/lei/cin (optional, format-validated)
        return new Envelope<>(SourceSystem.CRM, "simulated-crm", "CRM-CREATE-" + crmId, "sim-1.0", raw);
    }

    /** Default simulated sample list (used when a batch pull is invoked with no crmIds). */
    private List<Envelope<RawCrmPayload>> simulatedBatch() {
        List<Envelope<RawCrmPayload>> out = new ArrayList<>();
        for (int i = 1; i <= 3; i++) {
            out.add(simulatedBorrower("CRM-SAMPLE-" + i));
        }
        return out;
    }

    private Envelope<RawCrmPayload> liveBorrower(String crmId) {
        try {
            RawCrmPayload raw = http.get()
                    .uri(uri -> uri.path(createPath).queryParam("crmId", crmId).build())
                    .retrieve()
                    .body(RawCrmPayload.class);
            if (raw == null) {
                throw new ApiException(HttpStatus.BAD_GATEWAY,
                        "CRM live borrower fetch returned an empty payload for " + crmId);
            }
            return new Envelope<>(SourceSystem.CRM, liveVendor, "CRM-CREATE-LIVE-" + crmId, payloadVersion, raw);
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            log.warn("CRM live borrower fetch failed for {} ({})", crmId, e.getMessage());
            throw new ApiException(HttpStatus.BAD_GATEWAY,
                    "CRM live borrower fetch failed for " + crmId + ": " + e.getMessage());
        }
    }

    private List<Envelope<RawCrmPayload>> liveBatch() {
        try {
            RawCrmPayload[] raws = http.get()
                    .uri(uri -> uri.path(listPath).build())
                    .retrieve()
                    .body(RawCrmPayload[].class);
            List<Envelope<RawCrmPayload>> out = new ArrayList<>();
            if (raws != null) {
                for (RawCrmPayload raw : raws) {
                    String id = raw.crmId() == null ? "" : raw.crmId();
                    out.add(new Envelope<>(SourceSystem.CRM, liveVendor, "CRM-CREATE-LIVE-" + id, payloadVersion, raw));
                }
            }
            return out;
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            log.warn("CRM live borrower list fetch failed ({})", e.getMessage());
            throw new ApiException(HttpStatus.BAD_GATEWAY,
                    "CRM live borrower list fetch failed: " + e.getMessage());
        }
    }

    private long stableToken(String accountName, String subjectId) {
        CRC32 crc = new CRC32();
        crc.update(((accountName == null ? "" : accountName) + "|" + (subjectId == null ? "" : subjectId))
                .getBytes(StandardCharsets.UTF_8));
        return crc.getValue();
    }
}
