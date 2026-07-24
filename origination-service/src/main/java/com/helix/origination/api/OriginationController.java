package com.helix.origination.api;

import com.helix.origination.dto.Dtos.AddCollateralRequest;
import com.helix.origination.dto.Dtos.AddFacilityRequest;
import com.helix.origination.dto.Dtos.AddSublimitRequest;
import com.helix.origination.dto.Dtos.CellView;
import com.helix.origination.dto.Dtos.CreateApplicationRequest;
import com.helix.origination.dto.Dtos.CreditInputs;
import com.helix.origination.dto.Dtos.DealEnvelope;
import com.helix.origination.dto.Dtos.FacilityView;
import com.helix.origination.dto.Dtos.OverrideRequest;
import com.helix.origination.dto.Dtos.SpreadAnalysis;
import com.helix.origination.dto.Dtos.SpreadFromExtractionRequest;
import com.helix.origination.dto.Dtos.SpreadRequest;
import com.helix.origination.dto.Dtos.SpreadVersionDetail;
import com.helix.origination.dto.Dtos.SpreadVersionView;
import com.helix.origination.dto.Dtos.StatusUpdateRequest;
import com.helix.origination.dto.Dtos.UploadDocumentRequest;
import com.helix.origination.entity.Collateral;
import com.helix.origination.entity.Document;
import com.helix.origination.entity.LoanApplication;
import com.helix.origination.entity.ProposedFacility;
import com.helix.origination.entity.Sublimit;
import com.helix.origination.service.DocIntelligenceService;
import com.helix.origination.service.OriginationService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/applications")
public class OriginationController {

    private static final Logger log = LoggerFactory.getLogger(OriginationController.class);

    private final OriginationService origination;
    private final DocIntelligenceService docIntel;

    public OriginationController(OriginationService origination, DocIntelligenceService docIntel) {
        this.origination = origination;
        this.docIntel = docIntel;
    }

    /**
     * Chain classify → extract on upload so the analyst does not need a separate "Extract" click.
     * The extraction is an advisory SUGGESTED row (human confirm still required); it is fail-soft —
     * a governance-off jurisdiction or any extraction error must never fail the upload itself.
     */
    private Document autoExtract(Document doc, String actor) {
        if (doc != null && doc.getId() != null) {
            try {
                docIntel.extract(doc.getId(), actor);
            } catch (Exception e) {
                log.warn("Auto-extract on upload skipped for document #{} (non-fatal): {}",
                        doc.getId(), e.getMessage());
            }
        }
        return doc;
    }

    @PostMapping
    public LoanApplication create(@Valid @RequestBody CreateApplicationRequest req,
                                  @RequestHeader(value = "X-Actor", defaultValue = "rm.user") String actor) {
        return origination.create(req, actor);
    }

    @GetMapping
    public List<LoanApplication> list() {
        return origination.list();
    }

    @GetMapping("/by-counterparty/{counterpartyRef}")
    public List<LoanApplication> listByCounterparty(@PathVariable String counterpartyRef) {
        return origination.listByCounterparty(counterpartyRef);
    }

    @GetMapping("/{reference}")
    public LoanApplication get(@PathVariable String reference) {
        return origination.get(reference);
    }

    @PatchMapping("/{reference}/status")
    public LoanApplication updateStatus(@PathVariable String reference, @Valid @RequestBody StatusUpdateRequest req,
                                        @RequestHeader(value = "X-Actor", defaultValue = "system") String actor) {
        return origination.updateStatus(reference, req.status(), actor);
    }

    // ---- documents ----

    @PostMapping("/{reference}/documents")
    public Document upload(@PathVariable String reference, @Valid @RequestBody UploadDocumentRequest req,
                           @RequestHeader(value = "X-Actor", defaultValue = "analyst.user") String actor) {
        return autoExtract(origination.uploadDocument(reference, req, actor), actor);
    }

    /**
     * REAL multipart file upload for document capture. Accepts the actual file bytes, stores them
     * in the governed DMS, extracts the document's real text (PDFBox / UTF-8 / config-gated OCR),
     * classifies from filename AND content, and persists the Document row. Additive to the legacy
     * filename-only {@code POST /{reference}/documents} path, which is unchanged.
     */
    @PostMapping(value = "/{reference}/documents/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Document uploadFile(@PathVariable String reference,
                               @RequestParam("file") MultipartFile file,
                               @RequestParam(value = "declaredType", required = false) String declaredType,
                               @RequestHeader(value = "X-Actor", defaultValue = "analyst.user") String actor) {
        return autoExtract(origination.uploadDocumentFile(reference, file, declaredType, actor), actor);
    }

    @GetMapping("/{reference}/documents")
    public List<Document> documents(@PathVariable String reference) {
        return origination.documents(reference);
    }

    @PostMapping("/documents/{docId}/verify")
    public Document verifyDocument(@PathVariable Long docId,
                                   @RequestHeader(value = "X-Actor", defaultValue = "analyst.user") String actor) {
        return origination.verifyDocument(docId, actor);
    }

    // ---- spreading ----

    @PostMapping("/{reference}/spread")
    public SpreadAnalysis spread(@PathVariable String reference, @Valid @RequestBody SpreadRequest req,
                                 @RequestHeader(value = "X-Actor", defaultValue = "analyst.user") String actor) {
        return origination.spread(reference, req, actor);
    }

    @PatchMapping("/spread/cells/{cellId}/override")
    public CellView override(@PathVariable Long cellId, @Valid @RequestBody OverrideRequest req,
                             @RequestHeader(value = "X-Actor", defaultValue = "analyst.user") String actor) {
        return origination.overrideCell(cellId, req, actor);
    }

    @PostMapping("/{reference}/spread/confirm")
    public LoanApplication confirmSpread(@PathVariable String reference,
                                         @RequestHeader(value = "X-Actor", defaultValue = "analyst.user") String actor) {
        return origination.confirmSpread(reference, actor);
    }

    /**
     * AI-EXTRACT → GRID → HUMAN-CONFIRM: pre-fill a DRAFT spread from a CONFIRMED document
     * extraction. The result is advisory (unconfirmed); the authoritative figure is only set
     * when the analyst separately confirms the spread. Never auto-confirms.
     */
    @PostMapping("/{reference}/spread/from-extraction")
    public SpreadAnalysis spreadFromExtraction(@PathVariable String reference,
                                               @RequestBody(required = false) SpreadFromExtractionRequest req,
                                               @RequestHeader(value = "X-Actor", defaultValue = "analyst.user") String actor) {
        return origination.spreadFromExtraction(reference, req, actor);
    }

    @GetMapping("/{reference}/analysis")
    public SpreadAnalysis analysis(@PathVariable String reference) {
        return origination.analysis(reference);
    }

    /** Append-only spread version timeline — metadata only, oldest first (no snapshots). */
    @GetMapping("/{reference}/spread/versions")
    public List<SpreadVersionView> spreadVersions(@PathVariable String reference) {
        return origination.spreadVersionsFor(reference);
    }

    /** A single archived spread version including its full analysis snapshot. */
    @GetMapping("/{reference}/spread/versions/{versionNo}")
    public SpreadVersionDetail spreadVersion(@PathVariable String reference, @PathVariable int versionNo) {
        return origination.spreadVersionDetail(reference, versionNo);
    }

    @GetMapping("/{reference}/credit-inputs")
    public CreditInputs creditInputs(@PathVariable String reference) {
        return origination.creditInputs(reference);
    }

    // ---- proposed facilities (multi) ----

    @PostMapping("/{reference}/facilities")
    public ProposedFacility addFacility(@PathVariable String reference, @Valid @RequestBody AddFacilityRequest req,
                                        @RequestHeader(value = "X-Actor", defaultValue = "rm.user") String actor) {
        return origination.addFacility(reference, req, actor);
    }

    @GetMapping("/{reference}/facilities")
    public List<ProposedFacility> facilities(@PathVariable String reference) {
        return origination.facilitiesFor(reference);
    }

    @DeleteMapping("/facilities/{id}")
    public void removeFacility(@PathVariable Long id,
                               @RequestHeader(value = "X-Actor", defaultValue = "rm.user") String actor) {
        origination.removeFacility(id, actor);
    }

    /** Set the facility's rate type (FIXED / FLOATING with benchmark + spread + reset frequency). */
    @PostMapping("/{reference}/facilities/{facilityRef}/rate-type")
    public ProposedFacility setRateType(@PathVariable String reference, @PathVariable String facilityRef,
                                        @RequestBody Map<String, Object> req,
                                        @RequestHeader(value = "X-Actor", defaultValue = "rm.user") String actor) {
        String rateType = req.get("rateType") == null ? "FIXED" : String.valueOf(req.get("rateType"));
        String benchmark = req.get("benchmarkCode") == null ? null : String.valueOf(req.get("benchmarkCode"));
        Double spread = req.get("spreadBps") == null ? null : ((Number) req.get("spreadBps")).doubleValue();
        Integer reset = req.get("resetFrequencyMonths") == null ? null : ((Number) req.get("resetFrequencyMonths")).intValue();
        return origination.setRateType(reference, facilityRef, rateType, benchmark, spread, reset, actor);
    }

    /** Apply an APPROVED post-sanction amendment (called by decision-service; retry-safe). */
    @PostMapping("/{reference}/facilities/{facilityRef}/amend")
    public ProposedFacility applyAmendment(@PathVariable String reference, @PathVariable String facilityRef,
                                           @RequestBody Map<String, Object> req,
                                           @RequestHeader("X-Actor") String actor) {
        Double newAmount = req.get("newAmount") == null ? null : ((Number) req.get("newAmount")).doubleValue();
        Integer newTenor = req.get("newTenorMonths") == null ? null : ((Number) req.get("newTenorMonths")).intValue();
        String amendmentRef = req.get("amendmentRef") == null ? null : String.valueOf(req.get("amendmentRef"));
        return origination.applyAmendment(reference, facilityRef, newAmount, newTenor, amendmentRef, actor);
    }

    // ---- collaterals (multi) ----

    @PostMapping("/{reference}/collaterals")
    public Collateral addCollateral(@PathVariable String reference, @Valid @RequestBody AddCollateralRequest req,
                                    @RequestHeader(value = "X-Actor", defaultValue = "analyst.user") String actor) {
        return origination.addCollateral(reference, req, actor);
    }

    @GetMapping("/{reference}/collaterals")
    public List<Collateral> collaterals(@PathVariable String reference) {
        return origination.collateralsFor(reference);
    }

    @PostMapping("/collaterals/{id}/perfect")
    public Collateral perfect(@PathVariable Long id,
                              @RequestHeader(value = "X-Actor", defaultValue = "legal.officer") String actor) {
        return origination.perfect(id, actor);
    }

    @GetMapping("/{reference}/envelope")
    public DealEnvelope envelope(@PathVariable String reference) {
        return origination.envelope(reference);
    }

    /** Enriched facility list: each facility includes its sublimits + interchangeability groups. */
    @GetMapping("/{reference}/facilities/view")
    public List<FacilityView> facilityViews(@PathVariable String reference) {
        return origination.facilityViewsFor(reference);
    }

    // ---- sublimits ----

    @PostMapping("/facilities/{facilityId}/sublimits")
    public Sublimit addSublimit(@PathVariable Long facilityId, @Valid @RequestBody AddSublimitRequest req,
                                @RequestHeader(value = "X-Actor", defaultValue = "analyst.user") String actor) {
        return origination.addSublimit(facilityId, req, actor);
    }

    @GetMapping("/facilities/{facilityId}/sublimits")
    public List<Sublimit> sublimits(@PathVariable Long facilityId) {
        return origination.sublimitsFor(facilityId);
    }

    @DeleteMapping("/sublimits/{id}")
    public void removeSublimit(@PathVariable Long id,
                               @RequestHeader(value = "X-Actor", defaultValue = "analyst.user") String actor) {
        origination.removeSublimit(id, actor);
    }
}
