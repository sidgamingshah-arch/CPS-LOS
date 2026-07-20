package com.helix.counterparty.api;

import com.helix.common.ingest.Ingestion.Envelope;
import com.helix.common.ingest.Ingestion.Result;
import com.helix.counterparty.dto.IngestDtos.RawBureauPayload;
import com.helix.counterparty.dto.IngestDtos.RawCrmPayload;
import com.helix.counterparty.entity.BureauRecord;
import com.helix.counterparty.entity.CrmProfile;
import com.helix.counterparty.service.BureauIngestionService;
import com.helix.counterparty.service.CrmIngestionService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Inbound source-system connectors for a counterparty (PRD §8) — credit-bureau + CRM.
 * Each supports PUSH (external caller POSTs a raw vendor payload) and PULL (Helix fetches out;
 * simulated by default, live via env base-url). All ingested data is advisory INPUT carrying
 * provenance; it never becomes an authoritative figure. Mirrors the screening-ingest style.
 */
@RestController
@RequestMapping("/api/counterparties")
public class SourceIngestController {

    private final BureauIngestionService bureau;
    private final CrmIngestionService crm;

    public SourceIngestController(BureauIngestionService bureau, CrmIngestionService crm) {
        this.bureau = bureau;
        this.crm = crm;
    }

    // ---- credit bureau ----

    /** PUSH: ingest a raw credit-bureau vendor payload (idempotent, provenance-stamped). */
    @PostMapping("/{id}/ingest/bureau")
    public Result ingestBureau(@PathVariable Long id, @RequestBody Envelope<RawBureauPayload> envelope,
                               @RequestHeader(value = "X-Actor", defaultValue = "rm.user") String actor) {
        return bureau.ingest(id, envelope, actor);
    }

    /** PULL: fetch a bureau report via the config-gated source (simulated default), then ingest. */
    @PostMapping("/{id}/ingest/bureau/pull")
    public Result pullBureau(@PathVariable Long id,
                             @RequestHeader(value = "X-Actor", defaultValue = "rm.user") String actor) {
        return bureau.pull(id, actor);
    }

    /** Latest ingested bureau report for a counterparty (advisory INPUT). */
    @GetMapping("/{id}/bureau")
    public BureauRecord latestBureau(@PathVariable Long id) {
        return bureau.latest(id);
    }

    // ---- CRM ----

    /** PUSH: ingest a raw inbound CRM vendor payload (idempotent, provenance-stamped). */
    @PostMapping("/{id}/ingest/crm")
    public Result ingestCrm(@PathVariable Long id, @RequestBody Envelope<RawCrmPayload> envelope,
                            @RequestHeader(value = "X-Actor", defaultValue = "rm.user") String actor) {
        return crm.ingest(id, envelope, actor);
    }

    /** PULL: fetch a CRM profile via the config-gated source (simulated default), then ingest. */
    @PostMapping("/{id}/ingest/crm/pull")
    public Result pullCrm(@PathVariable Long id,
                          @RequestHeader(value = "X-Actor", defaultValue = "rm.user") String actor) {
        return crm.pull(id, actor);
    }

    /** Latest ingested CRM profile for a counterparty (advisory INPUT). */
    @GetMapping("/{id}/crm")
    public CrmProfile latestCrm(@PathVariable Long id) {
        return crm.latest(id);
    }
}
