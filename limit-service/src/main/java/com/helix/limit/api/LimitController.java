package com.helix.limit.api;

import com.helix.limit.dto.Dtos.AddChildRequest;
import com.helix.limit.dto.Dtos.CreateRootRequest;
import com.helix.limit.dto.Dtos.ExposureCheckResult;
import com.helix.limit.dto.Dtos.ExtendRequest;
import com.helix.limit.dto.Dtos.FreezeRequest;
import com.helix.limit.dto.Dtos.NodeView;
import com.helix.limit.dto.Dtos.TreeView;
import com.helix.limit.dto.Dtos.UtilisationRequest;
import com.helix.limit.dto.Dtos.UtilisationResponse;
import com.helix.limit.dto.Dtos.ValidationResult;
import com.helix.limit.entity.LimitNode;
import com.helix.limit.entity.Utilisation;
import com.helix.limit.service.LimitService;
import com.helix.limit.service.UtilisationService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/limits")
public class LimitController {

    private final LimitService limits;
    private final UtilisationService utilisation;

    public LimitController(LimitService limits, UtilisationService utilisation) {
        this.limits = limits;
        this.utilisation = utilisation;
    }

    // ---- construction ----

    @PostMapping("/root")
    public LimitNode createRoot(@Valid @RequestBody CreateRootRequest req,
                                @RequestHeader(value = "X-Actor", defaultValue = "credit.ops") String actor) {
        return limits.createRoot(req, actor);
    }

    @PostMapping("/{parentId}/child")
    public LimitNode addChild(@PathVariable Long parentId, @Valid @RequestBody AddChildRequest req,
                              @RequestHeader(value = "X-Actor", defaultValue = "credit.ops") String actor) {
        return limits.addChild(parentId, req, actor);
    }

    @PostMapping("/build/{applicationRef}")
    public TreeView build(@PathVariable String applicationRef,
                          @RequestHeader(value = "X-Actor", defaultValue = "credit.ops") String actor) {
        return limits.buildFromDeal(applicationRef, actor);
    }

    // ---- View API (product processor) ----

    @GetMapping("/view")
    public Object view(@RequestParam(required = false) String cif,
                       @RequestParam(required = false) String line) {
        if (line != null && !line.isBlank()) {
            return limits.node(line);
        }
        return limits.treeView(cif);
    }

    @GetMapping("/line/{reference}")
    public NodeView line(@PathVariable String reference) {
        return limits.node(reference);
    }

    @GetMapping("/{cif}/exposure")
    public ExposureCheckResult exposure(@PathVariable String cif) {
        return limits.exposureCheck(cif, 0);
    }

    @GetMapping("/{cif}/ledger")
    public List<Utilisation> ledger(@PathVariable String cif) {
        return utilisation.ledgerFor(cif);
    }

    // ---- Validation API ----

    @PostMapping("/validate")
    public ValidationResult validate(@RequestParam String cif, @RequestParam String line,
                                     @RequestParam double amount,
                                     @RequestParam(required = false) String currency,
                                     @RequestParam(required = false) Integer tenorMonths) {
        return utilisation.validate(cif, line, amount, currency, tenorMonths);
    }

    // ---- Utilisation API (UTILISE / RELEASE / RESERVE / REVERSAL; multi-action) ----

    @PostMapping("/utilise")
    public UtilisationResponse utilise(@Valid @RequestBody UtilisationRequest req,
                                       @RequestHeader(value = "X-Actor", defaultValue = "product.processor") String actor) {
        return utilisation.apply(req, actor);
    }

    // ---- maintenance ----

    @PostMapping("/{id}/freeze")
    public LimitNode freeze(@PathVariable Long id, @RequestBody(required = false) FreezeRequest req,
                            @RequestHeader(value = "X-Actor", defaultValue = "credit.ops") String actor) {
        return limits.setStatus(id, "FROZEN", req == null ? null : req.reason(), actor);
    }

    @PostMapping("/{id}/unfreeze")
    public LimitNode unfreeze(@PathVariable Long id,
                              @RequestHeader(value = "X-Actor", defaultValue = "credit.ops") String actor) {
        return limits.setStatus(id, "ACTIVE", null, actor);
    }

    @PostMapping("/{id}/extend")
    public LimitNode extend(@PathVariable Long id, @Valid @RequestBody ExtendRequest req,
                            @RequestHeader(value = "X-Actor", defaultValue = "credit.ops") String actor) {
        return limits.extend(id, LocalDate.parse(req.expiryDate()), actor);
    }
}
