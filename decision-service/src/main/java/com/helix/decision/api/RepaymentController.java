package com.helix.decision.api;

import com.helix.common.ingest.Ingestion;
import com.helix.common.ingest.SourceSystem;
import com.helix.decision.dto.RepaymentDtos.RecordRepaymentRequest;
import com.helix.decision.dto.RepaymentDtos.RejectRepaymentRequest;
import com.helix.decision.dto.RepaymentDtos.RepaymentIngestEnvelope;
import com.helix.decision.dto.RepaymentDtos.ScheduleView;
import com.helix.decision.entity.Repayment;
import com.helix.decision.service.RepaymentService;
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
 * Repayment API — the inbound money leg. Deterministic schedule (computed view),
 * the manual maker-checker lane (record → confirm books the limit RELEASE), and
 * the core-banking connector lane (idempotent envelope ingest).
 */
@RestController
@RequestMapping("/api/repayments")
public class RepaymentController {

    private final RepaymentService repayments;

    public RepaymentController(RepaymentService repayments) {
        this.repayments = repayments;
    }

    /** Deterministic forward schedule from current outstanding (EMI / EQUAL_PRINCIPAL / BULLET). */
    @GetMapping("/{reference}/schedule")
    public ScheduleView schedule(@PathVariable String reference,
                                 @RequestParam String facilityRef,
                                 @RequestParam(defaultValue = "EMI") String method,
                                 @RequestParam(defaultValue = "MONTHLY") String frequency) {
        return repayments.schedule(reference, facilityRef, method, frequency);
    }

    @GetMapping("/{reference}")
    public List<Repayment> history(@PathVariable String reference,
                                   @RequestParam(required = false) String facilityRef) {
        return repayments.historyFor(reference, facilityRef);
    }

    @GetMapping("/{reference}/outstanding")
    public java.util.Map<String, Object> outstanding(@PathVariable String reference,
                                                     @RequestParam String facilityRef) {
        return java.util.Map.of("applicationReference", reference, "facilityRef", facilityRef,
                "outstandingPrincipal", repayments.outstandingPrincipal(reference, facilityRef));
    }

    /** Maker lane: record a repayment (RECORDED — no ledger movement yet). */
    @PostMapping("/{reference}/record")
    public Repayment record(@PathVariable String reference,
                            @Valid @RequestBody RecordRepaymentRequest req,
                            @RequestHeader("X-Actor") String actor) {
        return repayments.record(reference, req.facilityRef(), req.amount(),
                req.principalComponent(), req.interestComponent(), req.valueDate(),
                req.narrative(), actor);
    }

    /** Checker lane: confirm books the limit RELEASE for the principal. SoD: confirmer ≠ recorder. */
    @PostMapping("/{id}/confirm")
    public Repayment confirm(@PathVariable Long id, @RequestHeader("X-Actor") String actor) {
        return repayments.confirm(id, actor);
    }

    @PostMapping("/{id}/reject")
    public Repayment reject(@PathVariable Long id, @Valid @RequestBody RejectRepaymentRequest req,
                            @RequestHeader("X-Actor") String actor) {
        return repayments.reject(id, req.reason(), actor);
    }

    /** Core-banking connector lane: idempotent on the envelope key; books RELEASE as SYSTEM. */
    @PostMapping("/{reference}/ingest")
    public Ingestion.Result ingest(@PathVariable String reference,
                                   @Valid @RequestBody RepaymentIngestEnvelope envelope,
                                   @RequestHeader(value = "X-Actor", defaultValue = "core-banking") String actor) {
        return repayments.ingest(reference, new Ingestion.Envelope<>(
                SourceSystem.CORE_BANKING, envelope.vendor(), envelope.idempotencyKey(),
                envelope.payloadVersion(), envelope.payload()), actor);
    }
}
