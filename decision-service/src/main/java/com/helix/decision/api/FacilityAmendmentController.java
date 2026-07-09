package com.helix.decision.api;

import com.helix.decision.entity.FacilityAmendment;
import com.helix.decision.service.FacilityAmendmentService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Post-sanction facility amendment API. Propose → DoA-routed approval (the
 * approver's role rank must cover the required authority) → applied to the
 * origination facility of record + the limit tree.
 */
@RestController
@RequestMapping("/api/amendments")
public class FacilityAmendmentController {

    private final FacilityAmendmentService amendments;

    public FacilityAmendmentController(FacilityAmendmentService amendments) {
        this.amendments = amendments;
    }

    public record ProposeRequest(@NotBlank String facilityRef, Double newAmount,
                                 Integer newTenorMonths, String reason) {
    }

    public record DecideRequest(String comment) {
    }

    public record RejectRequest(@NotBlank String reason) {
    }

    @PostMapping("/{reference}/propose")
    public FacilityAmendment propose(@PathVariable String reference,
                                     @Valid @RequestBody ProposeRequest req,
                                     @RequestHeader("X-Actor") String actor) {
        return amendments.propose(reference, req.facilityRef(), req.newAmount(),
                req.newTenorMonths(), req.reason(), actor);
    }

    @PostMapping("/{id}/approve")
    public FacilityAmendment approve(@PathVariable Long id,
                                     @RequestBody(required = false) DecideRequest req,
                                     @RequestHeader("X-Actor") String actor) {
        return amendments.approve(id, req == null ? null : req.comment(), actor);
    }

    @PostMapping("/{id}/reject")
    public FacilityAmendment reject(@PathVariable Long id, @Valid @RequestBody RejectRequest req,
                                    @RequestHeader("X-Actor") String actor) {
        return amendments.reject(id, req.reason(), actor);
    }

    @GetMapping("/{reference}")
    public List<FacilityAmendment> history(@PathVariable String reference) {
        return amendments.historyFor(reference);
    }
}
