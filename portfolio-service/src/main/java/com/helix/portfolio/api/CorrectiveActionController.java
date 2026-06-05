package com.helix.portfolio.api;

import com.helix.portfolio.entity.CorrectiveAction;
import com.helix.portfolio.service.CorrectiveActionService;
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
@RequestMapping("/api/cap")
public class CorrectiveActionController {

    public record RaiseRequest(@NotBlank String applicationReference, Long signalId,
                               @NotBlank String description, String criticality,
                               @NotBlank String targetDate, @NotBlank String owner,
                               Integer reminderDays, Integer escalationDays) {
    }

    public record RespondRequest(@NotBlank String response, String docRef) {
    }

    public record CloseRequest(String comment) {
    }

    public record EscalateRequest(@NotBlank String reason) {
    }

    private final CorrectiveActionService cap;

    public CorrectiveActionController(CorrectiveActionService cap) {
        this.cap = cap;
    }

    @PostMapping("/actions")
    public CorrectiveAction raise(@RequestBody RaiseRequest req,
                                  @RequestHeader(value = "X-Actor", defaultValue = "credit.officer") String actor) {
        return cap.raise(req.applicationReference(), req.signalId(), req.description(), req.criticality(),
                LocalDate.parse(req.targetDate()), req.owner(), req.reminderDays(), req.escalationDays(), actor);
    }

    @PostMapping("/actions/{id}/respond")
    public CorrectiveAction respond(@PathVariable Long id, @RequestBody RespondRequest req,
                                    @RequestHeader(value = "X-Actor", defaultValue = "rm.user") String actor) {
        return cap.respond(id, req.response(), req.docRef(), actor);
    }

    @PostMapping("/actions/{id}/close")
    public CorrectiveAction close(@PathVariable Long id, @RequestBody(required = false) CloseRequest req,
                                  @RequestHeader(value = "X-Actor", defaultValue = "credit.officer") String actor) {
        return cap.close(id, req == null ? null : req.comment(), actor);
    }

    @PostMapping("/actions/{id}/escalate")
    public CorrectiveAction escalate(@PathVariable Long id, @RequestBody EscalateRequest req,
                                     @RequestHeader(value = "X-Actor", defaultValue = "credit.officer") String actor) {
        return cap.escalate(id, req.reason(), actor);
    }

    @PostMapping("/sweep")
    public Map<String, Object> sweep(@RequestHeader(value = "X-Actor", defaultValue = "system") String actor) {
        return cap.sweep(actor);
    }

    @GetMapping("/{reference}")
    public List<CorrectiveAction> forApplication(@PathVariable String reference) {
        return cap.forApplication(reference);
    }

    @GetMapping("/inbox")
    public List<CorrectiveAction> inbox(@RequestParam(required = false) String owner,
                                        @RequestParam(required = false) String status) {
        if (owner != null && !owner.isBlank()) return cap.inboxFor(owner);
        if (status != null && !status.isBlank()) return cap.byStatus(status);
        return cap.byStatus("OPEN");
    }
}
