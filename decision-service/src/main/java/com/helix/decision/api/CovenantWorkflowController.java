package com.helix.decision.api;

import com.helix.decision.entity.CovenantAction;
import com.helix.decision.entity.CovenantSchedule;
import com.helix.decision.service.CovenantWorkflowService;
import jakarta.validation.constraints.NotBlank;
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
import java.util.Map;

@RestController
@RequestMapping("/api/covenants/tracking")
public class CovenantWorkflowController {

    public record InitRequest(@NotBlank String applicationRef, String startDate, String endDate) {
    }

    public record ExtensionRequest(@NotBlank String newDueDate, String reason) {
    }

    public record WaiverRequest(String reason) {
    }

    public record DecisionRequest(boolean approve, String comment) {
    }

    private final CovenantWorkflowService workflow;

    public CovenantWorkflowController(CovenantWorkflowService workflow) {
        this.workflow = workflow;
    }

    @PostMapping("/init")
    public List<CovenantSchedule> initialise(@RequestBody InitRequest req,
                                             @RequestHeader(value = "X-Actor", defaultValue = "analyst.user") String actor) {
        LocalDate start = req.startDate() == null ? LocalDate.now() : LocalDate.parse(req.startDate());
        LocalDate end = req.endDate() == null ? start.plusYears(3) : LocalDate.parse(req.endDate());
        return workflow.initialiseSchedules(req.applicationRef(), start, end, actor);
    }

    @GetMapping("/{reference}")
    public List<CovenantSchedule> list(@PathVariable String reference) {
        return workflow.list(reference);
    }

    @PostMapping("/{reference}/run-due")
    public List<CovenantSchedule> runDue(@PathVariable String reference,
                                         @RequestHeader(value = "X-Actor", defaultValue = "portfolio.manager") String actor) {
        return workflow.runDue(reference, actor);
    }

    @GetMapping("/upcoming")
    public List<CovenantSchedule> upcoming(@RequestParam(defaultValue = "30") int days) {
        return workflow.upcoming(days);
    }

    @PostMapping("/alerts/send")
    public Map<String, Object> alerts(@RequestParam(defaultValue = "30") int days,
                                      @RequestHeader(value = "X-Actor", defaultValue = "system") String actor) {
        return Map.of("alertsSent", workflow.sendUpcomingAlerts(days, actor));
    }

    @PostMapping("/schedules/{id}/request/extension")
    public CovenantAction requestExtension(@PathVariable Long id, @RequestBody ExtensionRequest req,
                                           @RequestHeader(value = "X-Actor", defaultValue = "rm.user") String actor) {
        return workflow.requestExtension(id, LocalDate.parse(req.newDueDate()), req.reason(), actor);
    }

    @PostMapping("/schedules/{id}/request/waiver")
    public CovenantAction requestWaiver(@PathVariable Long id, @RequestBody WaiverRequest req,
                                        @RequestHeader(value = "X-Actor", defaultValue = "rm.user") String actor) {
        return workflow.requestWaiver(id, req.reason(), actor);
    }

    @PostMapping("/schedules/{id}/freeze-accounts")
    public CovenantAction freeze(@PathVariable Long id, @RequestBody(required = false) WaiverRequest req,
                                 @RequestHeader(value = "X-Actor", defaultValue = "credit.officer") String actor) {
        return workflow.freezeAccounts(id, req == null ? null : req.reason(), actor);
    }

    @GetMapping("/schedules/{id}/actions")
    public List<CovenantAction> actions(@PathVariable Long id) {
        return workflow.actionsFor(id);
    }

    @PostMapping("/actions/{actionId}/decision")
    public CovenantAction decide(@PathVariable Long actionId, @RequestBody DecisionRequest req,
                                 @RequestHeader(value = "X-Actor", defaultValue = "credit.officer") String actor) {
        return workflow.decide(actionId, req.approve(), req.comment(), actor);
    }
}
