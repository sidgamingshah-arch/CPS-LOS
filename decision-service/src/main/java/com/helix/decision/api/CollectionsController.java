package com.helix.decision.api;

import com.helix.decision.entity.CollectionsCase;
import com.helix.decision.entity.FacilityAmendment;
import com.helix.decision.service.CollectionsService;
import com.helix.decision.service.CollectionsService.WriteOffProposal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
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
 * Collections / NPA workflow API. Open a case when a facility is overdue; update
 * its DPD (which restages it); restructure (chains FacilityAmendment), initiate
 * legal, or DoA-route a write-off; cure when the borrower clears the overdue.
 */
@RestController
@RequestMapping("/api/collections")
public class CollectionsController {

    private final CollectionsService collections;

    public CollectionsController(CollectionsService collections) {
        this.collections = collections;
    }

    public record OpenRequest(@NotBlank String facilityRef, @PositiveOrZero int daysPastDue,
                              @Positive double overdueAmount) { }

    public record UpdateDpdRequest(@PositiveOrZero int daysPastDue,
                                   @PositiveOrZero double overdueAmount, String note) { }

    public record AssignRequest(@NotBlank String assignee) { }

    public record CureRequest(String note) { }

    public record RestructureRequest(Double newAmount, Integer newTenorMonths, @NotBlank String reason) { }

    public record LegalRequest(@NotBlank String legalRef, String note) { }

    public record ProposeWriteOffRequest(@Positive double amount, @NotBlank String reason) { }

    public record DecideRequest(String comment) { }

    @PostMapping("/{reference}/open")
    public CollectionsCase open(@PathVariable String reference, @Valid @RequestBody OpenRequest req,
                                @RequestHeader("X-Actor") String actor) {
        return collections.open(reference, req.facilityRef(), req.daysPastDue(), req.overdueAmount(), actor);
    }

    public record MonitoringOpenRequest(int daysPastDue, double overdueAmount, String trigger) { }

    /**
     * SYSTEM auto-open from the portfolio monitoring sweep (EWS escalation). Idempotent;
     * not role-gated — the case shell is automation surfacing work, and every action
     * inside it stays human + DoA gated.
     */
    @PostMapping("/{reference}/monitoring/open")
    public CollectionsCase openFromMonitoring(@PathVariable String reference,
                                              @RequestBody MonitoringOpenRequest req) {
        return collections.openFromMonitoring(reference, req.daysPastDue(), req.overdueAmount(), req.trigger());
    }

    @PostMapping("/{id}/dpd")
    public CollectionsCase updateDpd(@PathVariable Long id, @Valid @RequestBody UpdateDpdRequest req,
                                     @RequestHeader("X-Actor") String actor) {
        return collections.updateDpd(id, req.daysPastDue(), req.overdueAmount(), req.note(), actor);
    }

    @PostMapping("/{id}/assign")
    public CollectionsCase assign(@PathVariable Long id, @Valid @RequestBody AssignRequest req,
                                  @RequestHeader("X-Actor") String actor) {
        return collections.assignTo(id, req.assignee(), actor);
    }

    @PostMapping("/{id}/cure")
    public CollectionsCase cure(@PathVariable Long id, @RequestBody(required = false) CureRequest req,
                                @RequestHeader("X-Actor") String actor) {
        return collections.cure(id, req == null ? null : req.note(), actor);
    }

    @PostMapping("/{id}/restructure/propose")
    public FacilityAmendment proposeRestructure(@PathVariable Long id, @Valid @RequestBody RestructureRequest req,
                                                @RequestHeader("X-Actor") String actor) {
        return collections.proposeRestructure(id, req.newAmount(), req.newTenorMonths(), req.reason(), actor);
    }

    @PostMapping("/{id}/restructure/applied")
    public CollectionsCase markRestructured(@PathVariable Long id, @RequestHeader("X-Actor") String actor) {
        return collections.markRestructured(id, actor);
    }

    @PostMapping("/{id}/legal")
    public CollectionsCase initiateLegal(@PathVariable Long id, @Valid @RequestBody LegalRequest req,
                                         @RequestHeader("X-Actor") String actor) {
        return collections.initiateLegal(id, req.legalRef(), req.note(), actor);
    }

    @PostMapping("/{id}/write-off/propose")
    public WriteOffProposal proposeWriteOff(@PathVariable Long id, @Valid @RequestBody ProposeWriteOffRequest req,
                                            @RequestHeader("X-Actor") String actor) {
        return collections.proposeWriteOff(id, req.amount(), req.reason(), actor);
    }

    @PostMapping("/{id}/write-off/approve")
    public CollectionsCase approveWriteOff(@PathVariable Long id, @RequestBody(required = false) DecideRequest req,
                                           @RequestHeader("X-Actor") String actor) {
        return collections.decideWriteOff(id, true, req == null ? null : req.comment(), actor);
    }

    @PostMapping("/{id}/write-off/reject")
    public CollectionsCase rejectWriteOff(@PathVariable Long id, @RequestBody(required = false) DecideRequest req,
                                          @RequestHeader("X-Actor") String actor) {
        return collections.decideWriteOff(id, false, req == null ? null : req.comment(), actor);
    }

    @GetMapping
    public List<CollectionsCase> list(@RequestParam(required = false) String reference) {
        return reference == null || reference.isBlank()
                ? collections.list() : collections.forApplication(reference);
    }

    @GetMapping("/{id}")
    public CollectionsCase get(@PathVariable Long id) {
        return collections.get(id);
    }
}
