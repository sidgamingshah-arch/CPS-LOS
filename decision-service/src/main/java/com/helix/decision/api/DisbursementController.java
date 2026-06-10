package com.helix.decision.api;

import com.helix.decision.dto.DisbursementDtos.AuthoriseRequest;
import com.helix.decision.dto.DisbursementDtos.RejectRequest;
import com.helix.decision.dto.DisbursementDtos.RequestDrawdownRequest;
import com.helix.decision.entity.Disbursement;
import com.helix.decision.service.DisbursementService;
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

/**
 * Pre-disbursement workflow API. The {@code POST /{reference}/request → /{id}/authorize
 * → /{id}/release} sequence implements the CP gate + maker-checker SoD around
 * drawdowns; release books a UTILISE on limit-service so the limit ledger stays
 * the single source of truth for exposure.
 */
@RestController
@RequestMapping("/api/disbursement")
public class DisbursementController {

    private final DisbursementService disb;

    public DisbursementController(DisbursementService disb) {
        this.disb = disb;
    }

    @PostMapping("/{reference}/request")
    public Disbursement request(@PathVariable String reference,
                                @Valid @RequestBody RequestDrawdownRequest req,
                                @RequestHeader(value = "X-Actor", defaultValue = "credit.ops") String actor) {
        return disb.request(reference, req.facilityRef(), req.amount(), req.currency(),
                req.purpose(), req.narrative(), actor);
    }

    @PostMapping("/{id}/authorize")
    public Disbursement authorize(@PathVariable Long id,
                                  @RequestBody(required = false) AuthoriseRequest req,
                                  @RequestHeader(value = "X-Actor", defaultValue = "credit.officer") String actor) {
        return disb.authorize(id, req == null ? null : req.note(), actor);
    }

    @PostMapping("/{id}/release")
    public Disbursement release(@PathVariable Long id,
                                @RequestHeader(value = "X-Actor", defaultValue = "credit.ops") String actor) {
        return disb.release(id, actor);
    }

    @PostMapping("/{id}/reject")
    public Disbursement reject(@PathVariable Long id, @Valid @RequestBody RejectRequest req,
                               @RequestHeader(value = "X-Actor", defaultValue = "credit.officer") String actor) {
        return disb.reject(id, req.reason(), actor);
    }

    @GetMapping("/{reference}")
    public List<Disbursement> history(@PathVariable String reference,
                                      @RequestParam(required = false) String facilityRef) {
        if (facilityRef == null || facilityRef.isBlank()) {
            return disb.historyFor(reference);
        }
        return disb.historyForFacility(reference, facilityRef);
    }
}
