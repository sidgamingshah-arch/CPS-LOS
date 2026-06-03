package com.helix.origination.api;

import com.helix.origination.dto.Dtos.AddCollateralRequest;
import com.helix.origination.dto.Dtos.AddFacilityRequest;
import com.helix.origination.dto.Dtos.CellView;
import com.helix.origination.dto.Dtos.CreateApplicationRequest;
import com.helix.origination.dto.Dtos.CreditInputs;
import com.helix.origination.dto.Dtos.DealEnvelope;
import com.helix.origination.dto.Dtos.OverrideRequest;
import com.helix.origination.dto.Dtos.SpreadAnalysis;
import com.helix.origination.dto.Dtos.SpreadRequest;
import com.helix.origination.dto.Dtos.StatusUpdateRequest;
import com.helix.origination.dto.Dtos.UploadDocumentRequest;
import com.helix.origination.entity.Collateral;
import com.helix.origination.entity.Document;
import com.helix.origination.entity.LoanApplication;
import com.helix.origination.entity.ProposedFacility;
import com.helix.origination.service.OriginationService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/applications")
public class OriginationController {

    private final OriginationService origination;

    public OriginationController(OriginationService origination) {
        this.origination = origination;
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
        return origination.uploadDocument(reference, req, actor);
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

    @GetMapping("/{reference}/analysis")
    public SpreadAnalysis analysis(@PathVariable String reference) {
        return origination.analysis(reference);
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
}
