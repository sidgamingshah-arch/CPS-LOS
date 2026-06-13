package com.helix.portfolio.api;

import com.helix.common.ingest.Ingestion.Envelope;
import com.helix.common.ingest.Ingestion.Result;
import com.helix.portfolio.dto.Dtos.ConcentrationView;
import com.helix.portfolio.dto.Dtos.DispositionRequest;
import com.helix.portfolio.dto.Dtos.PortfolioSummary;
import com.helix.portfolio.dto.Dtos.RegisterExposureRequest;
import com.helix.portfolio.dto.Dtos.StressResult;
import com.helix.portfolio.dto.IngestDtos.RawCoreBankingFeed;
import com.helix.portfolio.entity.EclResult;
import com.helix.portfolio.entity.EwsSignal;
import com.helix.portfolio.entity.ExposureRecord;
import com.helix.portfolio.entity.RarocTracking;
import com.helix.portfolio.service.CoreBankingIngestionService;
import com.helix.portfolio.service.EwsService;
import com.helix.portfolio.service.MultiDimConcentrationService;
import com.helix.portfolio.service.PortfolioService;
import com.helix.portfolio.service.RarocTrackingService;

import java.util.Map;
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

@RestController
@RequestMapping("/api/portfolio")
public class PortfolioController {

    private final PortfolioService portfolio;
    private final EwsService ews;
    private final CoreBankingIngestionService coreBanking;
    private final RarocTrackingService raroc;
    private final MultiDimConcentrationService multiDim;

    public PortfolioController(PortfolioService portfolio, EwsService ews, CoreBankingIngestionService coreBanking,
                               RarocTrackingService raroc, MultiDimConcentrationService multiDim) {
        this.portfolio = portfolio;
        this.ews = ews;
        this.coreBanking = coreBanking;
        this.raroc = raroc;
        this.multiDim = multiDim;
    }

    // ---- exposures ----

    @PostMapping("/exposures/{reference}/register")
    public ExposureRecord register(@PathVariable String reference,
                                   @RequestBody(required = false) RegisterExposureRequest req,
                                   @RequestHeader(value = "X-Actor", defaultValue = "credit.ops") String actor) {
        int dpd = req == null ? 0 : req.daysPastDue();
        return portfolio.register(reference, dpd, actor);
    }

    @GetMapping("/exposures")
    public List<ExposureRecord> exposures() {
        return portfolio.exposures();
    }

    @GetMapping("/exposures/{reference}")
    public ExposureRecord exposure(@PathVariable String reference) {
        return portfolio.exposure(reference);
    }

    /** Ingest a core-banking conduct/booking feed via the canonical connector (idempotent). */
    @PostMapping("/exposures/{reference}/ingest/core-banking")
    public Result ingestCoreBanking(@PathVariable String reference,
                                    @RequestBody Envelope<RawCoreBankingFeed> envelope,
                                    @RequestHeader(value = "X-Actor", defaultValue = "credit.ops") String actor) {
        return coreBanking.ingest(reference, envelope, actor);
    }

    // ---- ECL / provisioning ----

    @PostMapping("/exposures/{reference}/ecl")
    public EclResult computeEcl(@PathVariable String reference,
                                @RequestHeader(value = "X-Actor", defaultValue = "credit.ops") String actor) {
        return portfolio.computeEcl(reference, actor);
    }

    @GetMapping("/exposures/{reference}/ecl/latest")
    public EclResult latestEcl(@PathVariable String reference) {
        return portfolio.latestEcl(reference);
    }

    @GetMapping("/summary")
    public PortfolioSummary summary() {
        return portfolio.summary();
    }

    @GetMapping("/concentration")
    public ConcentrationView concentration(@RequestParam(defaultValue = "IN-RBI") String jurisdiction) {
        return portfolio.concentration(jurisdiction);
    }

    /**
     * Multi-dimensional concentration: 8 dimensions + 2 intersections, HHI per dim.
     * Scoped to the jurisdiction's own book by default; {@code global=true} cuts the
     * whole book with that jurisdiction's thresholds (group-CRO view).
     */
    @GetMapping("/concentration/multi")
    public com.helix.portfolio.dto.Dtos.MultiDimConcentrationView concentrationMulti(
            @RequestParam(defaultValue = "IN-RBI") String jurisdiction,
            @RequestParam(defaultValue = "false") boolean global) {
        return multiDim.concentration(jurisdiction, global);
    }

    /**
     * Correlation-stressed concentration: shock a sector's PD and propagate it through the
     * correlation matrix to every co-moving sector, rolling stressed expected loss against
     * the capital buffer. The "hidden" concentration that name diversification masks.
     */
    @PostMapping("/concentration/stress")
    public com.helix.portfolio.dto.Dtos.ConcentrationStressView concentrationStress(
            @RequestParam(defaultValue = "IN-RBI") String jurisdiction,
            @RequestParam(defaultValue = "false") boolean global,
            @org.springframework.web.bind.annotation.RequestBody com.helix.portfolio.dto.Dtos.StressRequest req) {
        return multiDim.stress(jurisdiction, req.shockedSector(), req.pdMultiplier(),
                req.capitalBufferPct(), req.correlationOverrides(), global);
    }

    @GetMapping("/stress")
    public StressResult stress() {
        return portfolio.stress();
    }

    // ---- EWS ----

    @PostMapping("/exposures/{reference}/ews/scan")
    public List<EwsSignal> scan(@PathVariable String reference,
                                @RequestHeader(value = "X-Actor", defaultValue = "portfolio.manager") String actor) {
        return portfolio.scan(reference, actor);
    }

    @PostMapping("/ews/scan-all")
    public List<EwsSignal> scanAll(@RequestHeader(value = "X-Actor", defaultValue = "portfolio.manager") String actor) {
        return portfolio.scanAll(actor);
    }

    @GetMapping("/ews/watchlist")
    public List<EwsSignal> watchlist() {
        return ews.watchlist();
    }

    @GetMapping("/exposures/{reference}/ews")
    public List<EwsSignal> signals(@PathVariable String reference) {
        return ews.forApplication(reference);
    }

    @PostMapping("/ews/{id}/disposition")
    public EwsSignal disposition(@PathVariable Long id, @Valid @RequestBody DispositionRequest req,
                                 @RequestHeader(value = "X-Actor", defaultValue = "portfolio.manager") String actor) {
        return ews.disposition(id, req.status(), actor);
    }

    // ---- projected vs actual RAROC tracking ----

    @PostMapping("/exposures/{reference}/raroc/snapshot")
    public RarocTracking snapshotProjected(@PathVariable String reference,
                                           @RequestHeader(value = "X-Actor", defaultValue = "credit.ops") String actor) {
        return raroc.snapshotOrigination(reference, actor);
    }

    @PostMapping("/exposures/{reference}/raroc/compute")
    public RarocTracking computeActualRaroc(@PathVariable String reference,
                                            @RequestParam(required = false) String period,
                                            @RequestParam(defaultValue = "0") double realisedProvisionDelta,
                                            @RequestHeader(value = "X-Actor", defaultValue = "portfolio.manager") String actor) {
        return raroc.computeActual(reference, period, realisedProvisionDelta, actor);
    }

    @GetMapping("/exposures/{reference}/raroc")
    public List<RarocTracking> rarocHistory(@PathVariable String reference) {
        return raroc.history(reference);
    }

    @GetMapping("/exposures/{reference}/raroc/latest")
    public RarocTracking rarocLatest(@PathVariable String reference) {
        return raroc.latest(reference);
    }

    @GetMapping("/raroc/variance")
    public Map<String, Object> bookRarocVariance() {
        return raroc.bookVariance();
    }
}
