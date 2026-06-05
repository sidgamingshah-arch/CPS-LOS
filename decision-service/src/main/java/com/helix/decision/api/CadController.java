package com.helix.decision.api;

import com.helix.decision.dto.CadDtos.CadCaseView;
import com.helix.decision.dto.CadDtos.DeviationDecisionRequest;
import com.helix.decision.dto.CadDtos.InitiateCadRequest;
import com.helix.decision.dto.CadDtos.LimitReleaseRequest;
import com.helix.decision.dto.CadDtos.RaiseDeviationRequest;
import com.helix.decision.dto.CadDtos.UpdateItemRequest;
import com.helix.decision.entity.CadCase;
import com.helix.decision.entity.ChecklistItem;
import com.helix.decision.entity.Deviation;
import com.helix.decision.service.CadService;
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

/** Credit Administration (CAD) — checklist, deviations, limit release. */
@RestController
@RequestMapping("/api/cad")
public class CadController {

    private final CadService cad;

    public CadController(CadService cad) {
        this.cad = cad;
    }

    @PostMapping("/cases")
    public CadCaseView initiate(@Valid @RequestBody InitiateCadRequest req,
                                @RequestHeader(value = "X-Actor", defaultValue = "cad.officer") String actor) {
        return cad.initiate(req, actor);
    }

    @GetMapping("/cases")
    public List<CadCase> inbox(@RequestParam(required = false) String status) {
        return cad.inbox(status);
    }

    @GetMapping("/cases/{id}")
    public CadCaseView view(@PathVariable Long id) {
        return cad.view(id);
    }

    @PostMapping("/items/{itemId}")
    public ChecklistItem updateItem(@PathVariable Long itemId, @Valid @RequestBody UpdateItemRequest req,
                                    @RequestHeader(value = "X-Actor", defaultValue = "cad.officer") String actor) {
        return cad.updateItem(itemId, req.status(), req.docRef(), req.comment(), actor);
    }

    @PostMapping("/items/{itemId}/deviation")
    public Deviation raiseDeviation(@PathVariable Long itemId, @Valid @RequestBody RaiseDeviationRequest req,
                                    @RequestHeader(value = "X-Actor", defaultValue = "cad.officer") String actor) {
        return cad.raiseDeviation(itemId, req, actor);
    }

    @PostMapping("/deviations/{devId}/decision")
    public Deviation decideDeviation(@PathVariable Long devId, @Valid @RequestBody DeviationDecisionRequest req,
                                     @RequestHeader(value = "X-Actor", defaultValue = "cad.checker") String actor) {
        return cad.decideDeviation(devId, req.approve(), req.comment(), actor);
    }

    @PostMapping("/cases/{id}/complete")
    public CadCaseView complete(@PathVariable Long id,
                                @RequestHeader(value = "X-Actor", defaultValue = "cad.officer") String actor) {
        return cad.complete(id, actor);
    }

    @PostMapping("/cases/{id}/limit-release")
    public CadCaseView limitRelease(@PathVariable Long id, @Valid @RequestBody LimitReleaseRequest req,
                                    @RequestHeader(value = "X-Actor", defaultValue = "cad.officer") String actor) {
        return cad.limitRelease(id, req, actor);
    }
}
