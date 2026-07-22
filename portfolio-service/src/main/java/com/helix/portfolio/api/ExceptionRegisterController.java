package com.helix.portfolio.api;

import com.helix.portfolio.dto.ExceptionDtos.AssignRequest;
import com.helix.portfolio.dto.ExceptionDtos.CreateTicklerRequest;
import com.helix.portfolio.dto.ExceptionDtos.ResolveRequest;
import com.helix.portfolio.dto.ExceptionDtos.RollupResult;
import com.helix.portfolio.entity.Tickler;
import com.helix.portfolio.service.ExceptionRegisterService;
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

/**
 * Unified exception / tickler register (U7). {@code GET /rollup} is a read-only aggregation
 * of open exception items across the platform; the tickler endpoints own a light manual
 * follow-up with maker-checker resolution (resolver != owner).
 */
@RestController
@RequestMapping("/api/exceptions")
public class ExceptionRegisterController {

    private final ExceptionRegisterService register;

    public ExceptionRegisterController(ExceptionRegisterService register) {
        this.register = register;
    }

    /** Read-only aggregated rollup. Best-effort — a down source degrades to a warning, not a failure. */
    @GetMapping("/rollup")
    public RollupResult rollup(@RequestParam(required = false) String subjectRef) {
        return register.rollup(subjectRef);
    }

    @PostMapping("/ticklers")
    public Tickler create(@Valid @RequestBody CreateTicklerRequest req,
                          @RequestHeader(value = "X-Actor", defaultValue = "portfolio.manager") String actor) {
        LocalDate dueAt = req.dueAt() == null || req.dueAt().isBlank() ? null : LocalDate.parse(req.dueAt());
        return register.create(req.subjectRef(), req.title(), req.description(), req.owner(),
                dueAt, req.priority(), actor);
    }

    @PostMapping("/ticklers/{ref}/assign")
    public Tickler assign(@PathVariable String ref, @Valid @RequestBody AssignRequest req,
                          @RequestHeader(value = "X-Actor", defaultValue = "portfolio.manager") String actor) {
        return register.assign(ref, req.toActor(), actor);
    }

    @PostMapping("/ticklers/{ref}/resolve")
    public Tickler resolve(@PathVariable String ref, @RequestBody(required = false) ResolveRequest req,
                           @RequestHeader(value = "X-Actor", defaultValue = "portfolio.manager") String actor) {
        return register.resolve(ref, req == null ? null : req.note(), actor);
    }

    @GetMapping("/ticklers")
    public List<Tickler> ticklers(@RequestParam(required = false) String status,
                                  @RequestParam(required = false) String subjectRef) {
        return register.list(status, subjectRef);
    }
}
