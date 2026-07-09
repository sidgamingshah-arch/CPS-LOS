package com.helix.counterparty.api;

import com.helix.common.ingest.Ingestion.Envelope;
import com.helix.common.ingest.Ingestion.Result;
import com.helix.counterparty.dto.Dtos.CloseRequest;
import com.helix.counterparty.dto.Dtos.CreateCounterpartyRequest;
import com.helix.counterparty.dto.Dtos.DispositionRequest;
import com.helix.counterparty.dto.Dtos.UboStructureRequest;
import com.helix.counterparty.dto.IngestDtos.RawScreeningPayload;
import com.helix.counterparty.entity.Counterparty;
import com.helix.counterparty.entity.ScreeningHit;
import com.helix.counterparty.entity.UboNode;
import com.helix.counterparty.service.CounterpartyService;
import com.helix.counterparty.service.ScreeningIngestionService;
import com.helix.counterparty.service.ScreeningService;
import com.helix.counterparty.service.UboService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/counterparties")
public class CounterpartyController {

    private final CounterpartyService counterparties;
    private final UboService ubo;
    private final ScreeningService screening;
    private final ScreeningIngestionService screeningIngestion;

    public CounterpartyController(CounterpartyService counterparties, UboService ubo, ScreeningService screening,
                                  ScreeningIngestionService screeningIngestion) {
        this.counterparties = counterparties;
        this.ubo = ubo;
        this.screening = screening;
        this.screeningIngestion = screeningIngestion;
    }

    @PostMapping
    public Counterparty create(@Valid @RequestBody CreateCounterpartyRequest req,
                               @RequestHeader(value = "X-Actor", defaultValue = "rm.user") String actor) {
        return counterparties.create(req, actor);
    }

    @GetMapping
    public List<Counterparty> list() {
        return counterparties.list();
    }

    @GetMapping("/{id}")
    public Counterparty get(@PathVariable Long id) {
        return counterparties.get(id);
    }

    @GetMapping("/by-reference/{reference}")
    public Counterparty getByReference(@PathVariable String reference) {
        return counterparties.getByReference(reference);
    }

    @PostMapping("/{id}/kyc/verify")
    public Counterparty verifyKyc(@PathVariable Long id,
                                  @RequestHeader(value = "X-Actor", defaultValue = "compliance.officer") String actor) {
        return counterparties.verifyKyc(id, actor);
    }

    /** Close (exit) an ACTIVE relationship — reachable CLOSED terminal state (D9). */
    @PostMapping("/{id}/close")
    public Counterparty close(@PathVariable Long id, @Valid @RequestBody CloseRequest req,
                              @RequestHeader(value = "X-Actor", defaultValue = "relationship.manager") String actor) {
        return counterparties.close(id, req.reason(), actor);
    }

    /** Re-KYC sweep — flags VERIFIED counterparties past their CDD-tier interval as RE_KYC_DUE (D9). */
    @PostMapping("/rekyc/sweep")
    public Map<String, Object> reKycSweep(@RequestParam(required = false) String asOf,
                                          @RequestHeader(value = "X-Actor", defaultValue = "system") String actor) {
        return counterparties.reKycSweep(asOf, actor);
    }

    // ---- UBO graph ----

    @PostMapping("/{id}/ubo")
    public List<UboNode> resolveUbo(@PathVariable Long id, @Valid @RequestBody UboStructureRequest req,
                                    @RequestHeader(value = "X-Actor", defaultValue = "compliance.officer") String actor) {
        Counterparty cp = counterparties.get(id);
        return ubo.resolve(id, cp.getReference(), req, actor);
    }

    @GetMapping("/{id}/ubo")
    public List<UboNode> uboGraph(@PathVariable Long id) {
        return ubo.graph(id);
    }

    // ---- screening ----

    @PostMapping("/{id}/screening/run")
    public List<ScreeningHit> runScreening(@PathVariable Long id,
                                           @RequestHeader(value = "X-Actor", defaultValue = "compliance.officer") String actor) {
        return screening.run(id, actor);
    }

    @GetMapping("/{id}/screening")
    public List<ScreeningHit> screeningHits(@PathVariable Long id) {
        return screening.forCounterparty(id);
    }

    @PostMapping("/screening/{hitId}/disposition")
    public ScreeningHit disposition(@PathVariable Long hitId, @Valid @RequestBody DispositionRequest req,
                                    @RequestHeader(value = "X-Actor", defaultValue = "compliance.officer") String actor) {
        return screening.disposition(hitId, req.disposition(), req.note(), actor);
    }

    /** Ingest a sanctions/screening vendor feed via the canonical connector (idempotent). */
    @PostMapping("/{id}/ingest/screening")
    public Result ingestScreening(@PathVariable Long id, @RequestBody Envelope<RawScreeningPayload> envelope,
                                  @RequestHeader(value = "X-Actor", defaultValue = "compliance.officer") String actor) {
        return screeningIngestion.ingest(id, envelope, actor);
    }
}
