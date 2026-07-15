package com.helix.origination.api;

import com.helix.origination.dto.ScfDtos.AddSpokeRequest;
import com.helix.origination.dto.ScfDtos.CreateProgramRequest;
import com.helix.origination.dto.ScfDtos.DecisionRequest;
import com.helix.origination.dto.ScfDtos.ProgramView;
import com.helix.origination.entity.ScfProgram;
import com.helix.origination.service.ScfService;
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
 * Supply-Chain Finance (SCF) product-paper API. Create an anchor-backed programme, add
 * spokes (deterministically judged against the pinned SCF_ELIGIBILITY snapshot), submit for
 * approval (raising a best-effort linked PRODUCT_PAPER noting), and approve/reject under
 * segregation of duties + credit authority. Every write takes X-Actor and is audited.
 */
@RestController
@RequestMapping("/api/scf")
public class ScfController {

    private final ScfService scf;

    public ScfController(ScfService scf) {
        this.scf = scf;
    }

    @PostMapping("/programs")
    public ProgramView create(@RequestBody CreateProgramRequest req,
                              @RequestHeader(value = "X-Actor", defaultValue = "rm.user") String actor) {
        return scf.createProgram(req, actor);
    }

    @GetMapping("/programs")
    public List<ScfProgram> list(@RequestParam(required = false) String anchorRef) {
        return scf.list(anchorRef);
    }

    @GetMapping("/programs/{scfRef}")
    public ProgramView get(@PathVariable String scfRef) {
        return scf.view(scfRef);
    }

    @PostMapping("/programs/{scfRef}/spokes")
    public ProgramView addSpoke(@PathVariable String scfRef, @RequestBody AddSpokeRequest req,
                                @RequestHeader(value = "X-Actor", defaultValue = "rm.user") String actor) {
        return scf.addSpoke(scfRef, req, actor);
    }

    @PostMapping("/programs/{scfRef}/submit")
    public ProgramView submit(@PathVariable String scfRef,
                              @RequestHeader(value = "X-Actor", defaultValue = "rm.user") String actor) {
        return scf.submit(scfRef, actor);
    }

    @PostMapping("/programs/{scfRef}/approve")
    public ProgramView approve(@PathVariable String scfRef,
                               @RequestBody(required = false) DecisionRequest req,
                               @RequestHeader(value = "X-Actor", defaultValue = "credit.head") String actor) {
        return scf.approve(scfRef, req == null ? null : req.note(), actor);
    }

    @PostMapping("/programs/{scfRef}/reject")
    public ProgramView reject(@PathVariable String scfRef,
                              @RequestBody(required = false) DecisionRequest req,
                              @RequestHeader(value = "X-Actor", defaultValue = "credit.head") String actor) {
        return scf.reject(scfRef, req == null ? null : req.note(), actor);
    }

    @PostMapping("/programs/{scfRef}/withdraw")
    public ProgramView withdraw(@PathVariable String scfRef,
                                @RequestHeader(value = "X-Actor", defaultValue = "rm.user") String actor) {
        return scf.withdraw(scfRef, actor);
    }
}
