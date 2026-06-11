package com.helix.decision.api;

import com.helix.decision.dto.PfDtos.CertifyRequest;
import com.helix.decision.dto.PfDtos.DefineMilestoneRequest;
import com.helix.decision.dto.PfDtos.DefineReserveRequest;
import com.helix.decision.dto.PfDtos.PfGateResult;
import com.helix.decision.dto.PfDtos.ReserveTxnRequest;
import com.helix.decision.entity.PfMilestone;
import com.helix.decision.entity.PfReserveAccount;
import com.helix.decision.service.PfService;
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
 * Project-finance post-drawdown mechanics API — construction milestones with the
 * LIE certification gate, and DSRA/TRA reserve accounts. The drawdown gate itself
 * is enforced inside the disbursement authorise path (PfService.assertDrawable).
 */
@RestController
@RequestMapping("/api/pf")
public class PfController {

    private final PfService pf;

    public PfController(PfService pf) {
        this.pf = pf;
    }

    // ---- milestones ----

    @PostMapping("/{reference}/milestones")
    public PfMilestone defineMilestone(@PathVariable String reference,
                                       @Valid @RequestBody DefineMilestoneRequest req,
                                       @RequestHeader(value = "X-Actor", defaultValue = "credit.ops") String actor) {
        java.time.LocalDate planned = null;
        if (req.plannedDate() != null && !req.plannedDate().isBlank()) {
            try { planned = java.time.LocalDate.parse(req.plannedDate()); }
            catch (Exception ignored) { /* leave null on parse failure */ }
        }
        return pf.defineMilestone(reference, req.facilityRef(), req.sequence(), req.name(),
                req.plannedAmount(), req.currency(), planned, actor);
    }

    @GetMapping("/{reference}/milestones")
    public List<PfMilestone> milestones(@PathVariable String reference,
                                        @RequestParam(required = false) String facilityRef) {
        return pf.milestonesFor(reference, facilityRef);
    }

    @PostMapping("/milestones/{id}/certify")
    public PfMilestone certify(@PathVariable Long id, @Valid @RequestBody CertifyRequest req,
                               @RequestHeader(value = "X-Actor", defaultValue = "lie.engineer") String actor) {
        return pf.lieCertify(id, req.certificationRef(), req.note(), actor);
    }

    // ---- reserves ----

    @PostMapping("/{reference}/reserves")
    public PfReserveAccount defineReserve(@PathVariable String reference,
                                          @Valid @RequestBody DefineReserveRequest req,
                                          @RequestHeader(value = "X-Actor", defaultValue = "credit.ops") String actor) {
        return pf.defineReserve(reference, req.accountType(), req.requiredAmount(), req.currency(), actor);
    }

    @GetMapping("/{reference}/reserves")
    public List<PfReserveAccount> reserves(@PathVariable String reference) {
        return pf.reservesFor(reference);
    }

    @PostMapping("/reserves/{id}/fund")
    public PfReserveAccount fund(@PathVariable Long id, @Valid @RequestBody ReserveTxnRequest req,
                                 @RequestHeader(value = "X-Actor", defaultValue = "treasury.ops") String actor) {
        return pf.fund(id, req.amount(), req.note(), actor);
    }

    @PostMapping("/reserves/{id}/withdraw")
    public PfReserveAccount withdraw(@PathVariable Long id, @Valid @RequestBody ReserveTxnRequest req,
                                     @RequestHeader(value = "X-Actor", defaultValue = "treasury.ops") String actor) {
        return pf.withdraw(id, req.amount(), req.note(), actor);
    }

    // ---- gate (read) ----

    @GetMapping("/gate/{reference}/{facilityRef}")
    public PfGateResult gate(@PathVariable String reference, @PathVariable String facilityRef,
                             @RequestParam(required = false) Integer milestoneSequence) {
        return pf.gate(reference, facilityRef, milestoneSequence);
    }
}
