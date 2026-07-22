package com.helix.origination.api;

import com.helix.origination.dto.BankingAsrDtos.ConfirmRequest;
import com.helix.origination.dto.BankingAsrDtos.CreateAsrRequest;
import com.helix.origination.entity.BankingAsr;
import com.helix.origination.service.BankingAsrService;
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
 * Banking Account Statement Review (ASR) — account-conduct analysis captured during
 * origination. Metrics are computed deterministically from the posted monthly lines; an
 * optional advisory narrative is drafted at the AI boundary and never mutates a metric.
 * Every write takes X-Actor and is audited; the ASR never mutates an authoritative figure.
 */
@RestController
@RequestMapping("/api/banking-asr")
public class BankingAsrController {

    private final BankingAsrService asr;

    public BankingAsrController(BankingAsrService asr) {
        this.asr = asr;
    }

    @PostMapping
    public BankingAsr create(@Valid @RequestBody CreateAsrRequest req,
                             @RequestHeader(value = "X-Actor", defaultValue = "analyst.user") String actor) {
        return asr.create(req, actor);
    }

    @GetMapping
    public List<BankingAsr> list(@RequestParam(required = false) String applicationRef) {
        return asr.list(applicationRef);
    }

    @GetMapping("/{asrRef}")
    public BankingAsr get(@PathVariable String asrRef) {
        return asr.get(asrRef);
    }

    @PostMapping("/{asrRef}/summary")
    public BankingAsr summary(@PathVariable String asrRef,
                              @RequestHeader(value = "X-Actor", defaultValue = "analyst.user") String actor) {
        return asr.summary(asrRef, actor);
    }

    @PostMapping("/{asrRef}/confirm")
    public BankingAsr confirm(@PathVariable String asrRef,
                              @RequestBody(required = false) ConfirmRequest req,
                              @RequestHeader(value = "X-Actor", defaultValue = "credit.officer") String actor) {
        return asr.confirm(asrRef, req == null ? null : req.note(), actor);
    }
}
