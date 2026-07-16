package com.helix.decision.api;

import com.helix.decision.dto.NotingDtos.CreateNotingRequest;
import com.helix.decision.dto.NotingDtos.DecisionNoteRequest;
import com.helix.decision.dto.NotingDtos.ReasonRequest;
import com.helix.decision.entity.Noting;
import com.helix.decision.service.NotingService;
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
 * Noting engine — governed decision records that route for approval (DoA / fixed-role,
 * driven by the NOTING_TYPE master), optionally require CAD authorisation, and support
 * reject / reverse / withdraw. Every write takes X-Actor and is audited; a noting never
 * mutates an authoritative figure.
 */
@RestController
@RequestMapping("/api/notings")
public class NotingController {

    private final NotingService notings;

    public NotingController(NotingService notings) {
        this.notings = notings;
    }

    @PostMapping
    public Noting create(@Valid @RequestBody CreateNotingRequest req,
                         @RequestHeader(value = "X-Actor", defaultValue = "rm.user") String actor) {
        return notings.create(req, actor);
    }

    @GetMapping
    public List<Noting> list(@RequestParam(required = false) String subjectRef,
                             @RequestParam(required = false) String status,
                             @RequestParam(required = false) String type) {
        return notings.list(subjectRef, status, type);
    }

    @GetMapping("/{ref}")
    public Noting get(@PathVariable String ref) {
        return notings.get(ref);
    }

    @PostMapping("/{ref}/submit")
    public Noting submit(@PathVariable String ref,
                         @RequestHeader(value = "X-Actor", defaultValue = "rm.user") String actor) {
        return notings.submit(ref, actor);
    }

    @PostMapping("/{ref}/approve")
    public Noting approve(@PathVariable String ref,
                          @RequestBody(required = false) DecisionNoteRequest req,
                          @RequestHeader(value = "X-Actor", defaultValue = "credit.officer") String actor) {
        return notings.approve(ref, req == null ? null : req.note(), actor);
    }

    @PostMapping("/{ref}/cad-authorize")
    public Noting cadAuthorize(@PathVariable String ref,
                               @RequestBody(required = false) DecisionNoteRequest req,
                               @RequestHeader(value = "X-Actor", defaultValue = "cad.maker") String actor) {
        return notings.cadAuthorize(ref, req == null ? null : req.note(), actor);
    }

    @PostMapping("/{ref}/reject")
    public Noting reject(@PathVariable String ref,
                         @RequestBody(required = false) ReasonRequest req,
                         @RequestHeader(value = "X-Actor", defaultValue = "credit.officer") String actor) {
        return notings.reject(ref, req == null ? null : req.reason(), actor);
    }

    @PostMapping("/{ref}/reverse")
    public Noting reverse(@PathVariable String ref,
                          @RequestBody(required = false) ReasonRequest req,
                          @RequestHeader(value = "X-Actor", defaultValue = "credit.officer") String actor) {
        return notings.reverse(ref, req == null ? null : req.reason(), actor);
    }

    @PostMapping("/{ref}/withdraw")
    public Noting withdraw(@PathVariable String ref,
                           @RequestHeader(value = "X-Actor", defaultValue = "rm.user") String actor) {
        return notings.withdraw(ref, actor);
    }
}
