package com.helix.decision.api;

import com.helix.decision.dto.CpDtos.AddCpRequest;
import com.helix.decision.dto.CpDtos.ClearRequest;
import com.helix.decision.dto.CpDtos.CpGateResult;
import com.helix.decision.dto.CpDtos.RejectRequest;
import com.helix.decision.dto.CpDtos.WaiveRequest;
import com.helix.decision.entity.ConditionPrecedent;
import com.helix.decision.service.ConditionPrecedentService;
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
 * Pre-disbursement Condition Precedent (CP) register API. Seeded at sanction-time
 * from {@code CP_MASTER}; mutations flow with named-human SoD. The
 * {@code /gate/{reference}/{facilityRef}} read is the pre-disbursement gate's
 * status — both the disbursement workflow and the UI consume it.
 */
@RestController
@RequestMapping("/api/cps")
public class ConditionPrecedentController {

    private final ConditionPrecedentService cps;

    public ConditionPrecedentController(ConditionPrecedentService cps) {
        this.cps = cps;
    }

    @PostMapping("/{reference}/seed")
    public List<ConditionPrecedent> seed(@PathVariable String reference,
                                         @RequestHeader(value = "X-Actor", defaultValue = "credit.ops") String actor) {
        return cps.seedFromMaster(reference, actor);
    }

    @PostMapping("/{reference}")
    public ConditionPrecedent addCustom(@PathVariable String reference,
                                        @Valid @RequestBody AddCpRequest req,
                                        @RequestHeader(value = "X-Actor", defaultValue = "credit.ops") String actor) {
        return cps.addCustom(reference, req.facilityRef(), req.code(), req.title(), req.description(),
                req.mandatory() == null ? true : req.mandatory(), actor);
    }

    @GetMapping("/{reference}")
    public List<ConditionPrecedent> register(@PathVariable String reference,
                                             @RequestParam(required = false) String facilityRef) {
        if (facilityRef == null || facilityRef.isBlank()) {
            return cps.register(reference);
        }
        return cps.registerForFacility(reference, facilityRef);
    }

    @GetMapping("/gate/{reference}/{facilityRef}")
    public CpGateResult gate(@PathVariable String reference, @PathVariable String facilityRef) {
        return cps.gate(reference, facilityRef);
    }

    @PostMapping("/{id}/clear")
    public ConditionPrecedent clear(@PathVariable Long id, @RequestBody(required = false) ClearRequest req,
                                    @RequestHeader(value = "X-Actor", defaultValue = "credit.ops") String actor) {
        return cps.clear(id, req == null ? null : req.evidenceRef(),
                req == null ? null : req.note(), actor);
    }

    @PostMapping("/{id}/waive")
    public ConditionPrecedent waive(@PathVariable Long id, @Valid @RequestBody WaiveRequest req,
                                    @RequestHeader(value = "X-Actor", defaultValue = "credit.officer") String actor) {
        return cps.waive(id, req.reason(), actor);
    }

    @PostMapping("/{id}/reject")
    public ConditionPrecedent reject(@PathVariable Long id, @Valid @RequestBody RejectRequest req,
                                     @RequestHeader(value = "X-Actor", defaultValue = "credit.ops") String actor) {
        return cps.reject(id, req.reason(), actor);
    }
}
