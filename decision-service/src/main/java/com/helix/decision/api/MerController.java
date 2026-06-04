package com.helix.decision.api;

import com.helix.decision.dto.MerDtos.RaiseRequest;
import com.helix.decision.dto.MerDtos.SubmitRequest;
import com.helix.decision.dto.MerDtos.VerifyRequest;
import com.helix.decision.dto.MerDtos.WaiveRequest;
import com.helix.decision.entity.MerItem;
import com.helix.decision.service.MerService;
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
import java.util.Map;

/**
 * Monitoring of Exceptions &amp; Renewals (MER) register — deferred-document tracking,
 * conditions subsequent, and recurring renewals (insurance / valuation / annual
 * review) with reminders, escalation sweep, maker-checker clearance and a DMS feed.
 */
@RestController
@RequestMapping("/api/mer")
public class MerController {

    private final MerService mer;

    public MerController(MerService mer) {
        this.mer = mer;
    }

    /** Builds the register from a completed CAD case (waived docs + collateral renewals). */
    @PostMapping("/generate/from-cad/{caseId}")
    public List<MerItem> generate(@PathVariable Long caseId,
                                  @RequestParam(required = false) String owner,
                                  @RequestHeader(value = "X-Actor", defaultValue = "cad.officer") String actor) {
        return mer.generateFromCad(caseId, owner, actor);
    }

    @PostMapping("/raise")
    public MerItem raise(@Valid @RequestBody RaiseRequest req,
                         @RequestHeader(value = "X-Actor", defaultValue = "rm.user") String actor) {
        return mer.raise(req, actor);
    }

    @GetMapping("/{reference}")
    public List<MerItem> list(@PathVariable String reference) {
        return mer.list(reference);
    }

    @GetMapping("/inbox")
    public List<MerItem> inbox(@RequestParam(required = false) String owner,
                               @RequestParam(required = false) String status) {
        return mer.inbox(owner, status);
    }

    @GetMapping("/summary")
    public Map<String, Object> summary(@RequestParam(required = false) String reference) {
        return mer.summary(reference);
    }

    @PostMapping("/{id}/submit")
    public MerItem submit(@PathVariable Long id, @Valid @RequestBody SubmitRequest req,
                          @RequestHeader(value = "X-Actor", defaultValue = "rm.user") String actor) {
        return mer.submit(id, req.docRef(), req.comment(), actor);
    }

    @PostMapping("/{id}/verify")
    public MerItem verify(@PathVariable Long id, @RequestBody VerifyRequest req,
                          @RequestHeader(value = "X-Actor", defaultValue = "cad.officer") String actor) {
        return mer.verify(id, req.approve(), req.comment(), actor);
    }

    @PostMapping("/{id}/waive")
    public MerItem waive(@PathVariable Long id, @Valid @RequestBody WaiveRequest req,
                         @RequestHeader(value = "X-Actor", defaultValue = "credit.officer") String actor) {
        return mer.waive(id, req.reason(), actor);
    }

    @PostMapping("/sweep")
    public Map<String, Object> sweep(@RequestHeader(value = "X-Actor", defaultValue = "system") String actor) {
        return mer.sweep(actor);
    }

    @GetMapping("/upcoming")
    public List<MerItem> upcoming(@RequestParam(defaultValue = "30") int days) {
        return mer.upcoming(days);
    }

    @PostMapping("/reminders/send")
    public Map<String, Object> reminders(@RequestParam(defaultValue = "30") int days,
                                         @RequestHeader(value = "X-Actor", defaultValue = "system") String actor) {
        return Map.of("remindersSent", mer.sendReminders(days, actor));
    }
}
