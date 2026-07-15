package com.helix.origination.api;

import com.helix.origination.dto.IpNoteDtos.CreateIpNoteRequest;
import com.helix.origination.dto.IpNoteDtos.DecisionNoteRequest;
import com.helix.origination.dto.IpNoteDtos.ReasonRequest;
import com.helix.origination.entity.IpNote;
import com.helix.origination.service.IpNoteService;
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
 * In-Principle (IP) note engine — a lightweight sponsorship record raised before the full
 * application. It routes for a named-human credit sign-off (SoD + authority-tier gated),
 * and once APPROVED converts into a real LoanApplication via the existing origination
 * application-creation path. Every write takes X-Actor and is audited; an IP note never
 * mutates an authoritative figure.
 */
@RestController
@RequestMapping("/api/ip-notes")
public class IpNoteController {

    private final IpNoteService ipNotes;

    public IpNoteController(IpNoteService ipNotes) {
        this.ipNotes = ipNotes;
    }

    @PostMapping
    public IpNote create(@Valid @RequestBody CreateIpNoteRequest req,
                         @RequestHeader(value = "X-Actor", defaultValue = "rm.user") String actor) {
        return ipNotes.create(req, actor);
    }

    @GetMapping
    public List<IpNote> list(@RequestParam(required = false) String counterpartyRef,
                             @RequestParam(required = false) String status) {
        return ipNotes.list(counterpartyRef, status);
    }

    @GetMapping("/{ref}")
    public IpNote get(@PathVariable String ref) {
        return ipNotes.get(ref);
    }

    @PostMapping("/{ref}/submit")
    public IpNote submit(@PathVariable String ref,
                         @RequestHeader(value = "X-Actor", defaultValue = "rm.user") String actor) {
        return ipNotes.submit(ref, actor);
    }

    @PostMapping("/{ref}/approve")
    public IpNote approve(@PathVariable String ref,
                          @RequestBody(required = false) DecisionNoteRequest req,
                          @RequestHeader(value = "X-Actor", defaultValue = "credit.officer") String actor) {
        return ipNotes.approve(ref, req == null ? null : req.note(), actor);
    }

    @PostMapping("/{ref}/reject")
    public IpNote reject(@PathVariable String ref,
                         @RequestBody(required = false) ReasonRequest req,
                         @RequestHeader(value = "X-Actor", defaultValue = "credit.officer") String actor) {
        return ipNotes.reject(ref, req == null ? null : req.reason(), actor);
    }

    @PostMapping("/{ref}/withdraw")
    public IpNote withdraw(@PathVariable String ref,
                           @RequestHeader(value = "X-Actor", defaultValue = "rm.user") String actor) {
        return ipNotes.withdraw(ref, actor);
    }

    @PostMapping("/{ref}/convert")
    public IpNote convert(@PathVariable String ref,
                          @RequestHeader(value = "X-Actor", defaultValue = "credit.officer") String actor) {
        return ipNotes.convert(ref, actor);
    }
}
