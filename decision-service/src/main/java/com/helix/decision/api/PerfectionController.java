package com.helix.decision.api;

import com.helix.decision.dto.PerfectionDtos.CaseView;
import com.helix.decision.dto.PerfectionDtos.CreateCaseRequest;
import com.helix.decision.dto.PerfectionDtos.StepActionRequest;
import com.helix.decision.dto.PerfectionDtos.VendorRfqRequest;
import com.helix.decision.entity.PerfectionCase;
import com.helix.decision.service.PerfectionService;
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

/** Mortgage / MOE security-perfection — cases, ordered role-gated steps, vendor RFQ. */
@RestController
@RequestMapping("/api/perfection")
public class PerfectionController {

    private final PerfectionService perfection;

    public PerfectionController(PerfectionService perfection) {
        this.perfection = perfection;
    }

    @PostMapping("/cases")
    public CaseView create(@Valid @RequestBody CreateCaseRequest req,
                           @RequestHeader(value = "X-Actor", defaultValue = "cad.ops") String actor) {
        return perfection.createCase(req, actor);
    }

    @GetMapping("/cases")
    public List<PerfectionCase> list(@RequestParam(required = false) String subjectRef,
                                     @RequestParam(required = false) String status) {
        return perfection.list(subjectRef, status);
    }

    @GetMapping("/cases/{perfRef}")
    public CaseView view(@PathVariable String perfRef) {
        return perfection.view(perfRef);
    }

    @PostMapping("/cases/{perfRef}/steps/{stepKey}/complete")
    public CaseView complete(@PathVariable String perfRef, @PathVariable String stepKey,
                             @Valid @RequestBody StepActionRequest req,
                             @RequestHeader(value = "X-Actor", defaultValue = "cad.ops") String actor) {
        return perfection.completeStep(perfRef, stepKey, req, actor);
    }

    @PostMapping("/cases/{perfRef}/steps/{stepKey}/waive")
    public CaseView waive(@PathVariable String perfRef, @PathVariable String stepKey,
                          @Valid @RequestBody StepActionRequest req,
                          @RequestHeader(value = "X-Actor", defaultValue = "cad.ops") String actor) {
        return perfection.waiveStep(perfRef, stepKey, req, actor);
    }

    @PostMapping("/cases/{perfRef}/steps/{stepKey}/vendor-rfq")
    public CaseView vendorRfq(@PathVariable String perfRef, @PathVariable String stepKey,
                              @RequestBody(required = false) VendorRfqRequest req,
                              @RequestHeader(value = "X-Actor", defaultValue = "cad.ops") String actor) {
        return perfection.vendorRfq(perfRef, stepKey, req, actor);
    }
}
