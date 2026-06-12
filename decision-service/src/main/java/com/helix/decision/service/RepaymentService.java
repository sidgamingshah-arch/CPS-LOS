package com.helix.decision.service;

import com.helix.common.audit.AuditService;
import com.helix.common.rbac.ActorDirectory;
import com.helix.common.rbac.ProtectedAction;
import com.helix.common.ingest.Canonical;
import com.helix.common.ingest.Ingestion;
import com.helix.common.ingest.IngestionGuard;
import com.helix.common.ingest.SourceSystem;
import com.helix.common.web.ApiException;
import com.helix.decision.client.LimitClient;
import com.helix.decision.client.UpstreamClient;
import com.helix.decision.client.UpstreamClient.DealEnvelopeDto;
import com.helix.decision.client.UpstreamClient.FacilityViewDto;
import com.helix.decision.client.UpstreamClient.RiskSummaryDto;
import com.helix.decision.dto.RepaymentDtos.RawRepaymentEvent;
import com.helix.decision.dto.RepaymentDtos.ScheduleRow;
import com.helix.decision.dto.RepaymentDtos.ScheduleView;
import com.helix.decision.entity.Disbursement;
import com.helix.decision.entity.Repayment;
import com.helix.decision.repo.DisbursementRepository;
import com.helix.decision.repo.RepaymentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The inbound money leg: deterministic repayment schedules + the repayment
 * register with two entry channels (manual maker-checker, core-banking connector).
 *
 * <p>The schedule is a computed view, never persisted — it derives from the
 * released-draw ledger (principal), the pricing of record (rate) and the facility
 * (tenor), so it can't drift from its sources. Confirmed repayments book a
 * {@code RELEASE} on the facility's limit node for the principal component; the
 * limit ledger stays the single source of truth for exposure.</p>
 */
@Service
public class RepaymentService {

    private final RepaymentRepository repo;
    private final DisbursementRepository disbursements;
    private final CoreBankingRepaymentConnector connector;
    private final IngestionGuard guard;
    private final UpstreamClient upstream;
    private final LimitClient limits;
    private final ActorDirectory roles;
    private final AuditService audit;

    public RepaymentService(RepaymentRepository repo, DisbursementRepository disbursements,
                            CoreBankingRepaymentConnector connector, IngestionGuard guard,
                            UpstreamClient upstream, LimitClient limits,
                            ActorDirectory roles, AuditService audit) {
        this.repo = repo;
        this.disbursements = disbursements;
        this.connector = connector;
        this.guard = guard;
        this.upstream = upstream;
        this.limits = limits;
        this.roles = roles;
        this.audit = audit;
    }

    // ============================================================ schedule (computed, deterministic)

    /**
     * Forward repayment plan from the facility's CURRENT outstanding principal
     * (released − reversed draws − confirmed principal repayments). Methods:
     * EMI (annuity), EQUAL_PRINCIPAL, BULLET; frequency MONTHLY or QUARTERLY.
     */
    @Transactional(readOnly = true)
    public ScheduleView schedule(String applicationReference, String facilityRef,
                                 String method, String frequency) {
        DealEnvelopeDto env = upstream.envelope(applicationReference);
        if (env == null) throw ApiException.notFound("No deal envelope for " + applicationReference);
        FacilityViewDto facility = findFacility(env, facilityRef);
        if (facility == null) {
            throw ApiException.badRequest("No facility '" + facilityRef + "' on " + applicationReference);
        }
        double principal = outstandingPrincipal(applicationReference, facilityRef);
        if (principal <= 0) {
            throw ApiException.conflict("Nothing outstanding on " + facilityRef
                    + " — release a drawdown before generating a schedule");
        }

        String m = method == null || method.isBlank() ? "EMI" : method.toUpperCase();
        String f = frequency == null || frequency.isBlank() ? "MONTHLY" : frequency.toUpperCase();
        int periodMonths = switch (f) {
            case "MONTHLY" -> 1;
            case "QUARTERLY" -> 3;
            default -> throw ApiException.badRequest("Unknown frequency '" + f + "' — MONTHLY or QUARTERLY");
        };
        int n = Math.max(1, facility.tenorMonths() / periodMonths);

        // Resolve the base annual rate and decide whether it's fixed across the
        // schedule (FIXED) or recomputed per reset period (FLOATING). The benchmark
        // master is the source of truth at the reset boundary; periods between
        // resets carry the rate from the previous boundary.
        boolean floating = "FLOATING".equalsIgnoreCase(facility.rateType());
        int resetMonths = floating && facility.resetFrequencyMonths() != null
                ? facility.resetFrequencyMonths() : periodMonths;
        Double benchmarkRate = null;
        double annualRate;
        String rateSource;
        if (floating && facility.benchmarkCode() != null) {
            benchmarkRate = upstream.benchmarkRate(facility.benchmarkCode());
            if (benchmarkRate == null) {
                throw ApiException.conflict("Benchmark " + facility.benchmarkCode()
                        + " not found in the BENCHMARK master — cannot price floating facility");
            }
            annualRate = benchmarkRate + (facility.spreadBps() == null ? 0.0 : facility.spreadBps() / 10_000.0);
            rateSource = "BENCHMARK:" + facility.benchmarkCode() + "+" + facility.spreadBps() + "bps";
        } else {
            RiskSummaryDto risk = upstream.riskSummaryOrNull(applicationReference);
            if (risk != null && risk.pricing() != null && risk.pricing().recommendedRate() > 0) {
                annualRate = risk.pricing().recommendedRate();
                rateSource = "PRICING_OF_RECORD";
            } else if (facility.indicativeRate() != null && facility.indicativeRate() > 0) {
                annualRate = facility.indicativeRate();
                rateSource = "FACILITY_INDICATIVE";
            } else {
                annualRate = 0.10;
                rateSource = "DEFAULT";
            }
        }
        double r = annualRate * periodMonths / 12.0;

        List<ScheduleRow> rows = new ArrayList<>();
        double bal = principal, totalPayment = 0, totalInterest = 0;
        LocalDate start = LocalDate.now();
        switch (m) {
            case "EMI" -> {
                // For FLOATING with a flat benchmark, EMI is recomputed on the
                // remaining balance whenever the period rate changes. That keeps
                // the schedule honest even if a future benchmark update lands on
                // a reset boundary in the past (replayed schedule call picks it up).
                double currentR = r;
                int remaining = n;
                double pmt = currentR == 0 ? principal / n : principal * currentR / (1 - Math.pow(1 + currentR, -n));
                for (int k = 1; k <= n; k++) {
                    double interest = round2(bal * currentR);
                    double prin = k == n ? round2(bal) : round2(pmt - interest);
                    double payment = round2(prin + interest);
                    rows.add(new ScheduleRow(k, start.plusMonths((long) k * periodMonths).toString(),
                            round2(bal), payment, prin, interest, round2(bal - prin),
                            annualRate));
                    bal -= prin;
                    totalPayment += payment;
                    totalInterest += interest;
                    remaining--;
                    // FLOATING + reset boundary: recompute EMI on remaining balance.
                    // (The first cut treats the benchmark as held flat across the
                    // schedule; the path is here for when a per-reset benchmark
                    // lookup is wired in via #benchmarkRateOn(asOf).)
                    if (floating && remaining > 0 && (k * periodMonths) % resetMonths == 0) {
                        currentR = annualRate * periodMonths / 12.0;
                        pmt = currentR == 0 ? bal / remaining
                                : bal * currentR / (1 - Math.pow(1 + currentR, -remaining));
                    }
                }
            }
            case "EQUAL_PRINCIPAL" -> {
                double prinConst = principal / n;
                for (int k = 1; k <= n; k++) {
                    double interest = round2(bal * r);
                    double prin = k == n ? round2(bal) : round2(prinConst);
                    double payment = round2(prin + interest);
                    rows.add(new ScheduleRow(k, start.plusMonths((long) k * periodMonths).toString(),
                            round2(bal), payment, prin, interest, round2(bal - prin), annualRate));
                    bal -= prin;
                    totalPayment += payment;
                    totalInterest += interest;
                }
            }
            case "BULLET" -> {
                for (int k = 1; k <= n; k++) {
                    double interest = round2(principal * r);
                    double prin = k == n ? round2(principal) : 0.0;
                    double payment = round2(prin + interest);
                    rows.add(new ScheduleRow(k, start.plusMonths((long) k * periodMonths).toString(),
                            round2(principal), payment, prin, interest, k == n ? 0.0 : round2(principal),
                            annualRate));
                    totalPayment += payment;
                    totalInterest += interest;
                }
            }
            default -> throw ApiException.badRequest(
                    "Unknown method '" + m + "' — EMI, EQUAL_PRINCIPAL or BULLET");
        }
        return new ScheduleView(applicationReference, facilityRef, m, f, round2(principal),
                annualRate, rateSource, n, round2(totalPayment), round2(totalInterest), rows);
    }

    // ============================================================ manual lane (maker-checker)

    @Transactional
    public Repayment record(String applicationReference, String facilityRef, double amount,
                            Double principalComponent, Double interestComponent,
                            String valueDate, String narrative, String actor) {
        requireActor(actor);
        roles.require(actor, ProtectedAction.REPAYMENT_RECORD);
        DealEnvelopeDto env = upstream.envelope(applicationReference);
        if (env == null) throw ApiException.notFound("No deal envelope for " + applicationReference);
        FacilityViewDto facility = findFacility(env, facilityRef);
        if (facility == null) {
            throw ApiException.badRequest("No facility '" + facilityRef + "' on " + applicationReference);
        }
        double principal, interest;
        if (principalComponent == null && interestComponent == null) {
            principal = amount;
            interest = 0.0;
        } else {
            principal = principalComponent == null ? 0.0 : principalComponent;
            interest = interestComponent == null ? 0.0 : interestComponent;
            if (Math.abs(principal + interest - amount) > 0.01) {
                throw ApiException.badRequest("principal %.2f + interest %.2f must equal amount %.2f"
                        .formatted(principal, interest, amount));
            }
        }
        // The principal leg must fit inside what is actually outstanding — counting
        // pending (RECORDED) repayments too, so two makers can't double-book the
        // same outstanding before either is confirmed.
        double outstanding = outstandingPrincipal(applicationReference, facilityRef);
        double pendingPrincipal = repo.findByApplicationReferenceAndFacilityRefAndStatusIn(
                        applicationReference, facilityRef, List.of("RECORDED")).stream()
                .mapToDouble(Repayment::getPrincipalComponent).sum();
        if (principal > outstanding - pendingPrincipal + 0.01) {
            throw ApiException.badRequest(
                    "Principal %.2f exceeds outstanding %.2f (with %.2f already pending confirmation) on %s"
                            .formatted(principal, outstanding, pendingPrincipal, facilityRef));
        }

        Repayment p = new Repayment();
        p.setApplicationReference(applicationReference);
        p.setFacilityRef(facilityRef);
        p.setAmount(round2(amount));
        p.setPrincipalComponent(round2(principal));
        p.setInterestComponent(round2(interest));
        p.setCurrency(facility.currency() == null ? "INR" : facility.currency().toUpperCase());
        p.setValueDate(valueDate == null || valueDate.isBlank() ? LocalDate.now() : LocalDate.parse(valueDate));
        p.setSource("MANUAL");
        p.setNarrative(narrative);
        p.setStatus("RECORDED");
        p.setRecordedBy(actor);
        Repayment saved = repo.save(p);
        audit.human(actor, "REPAYMENT_RECORDED", "Repayment", String.valueOf(saved.getId()),
                "Repayment of %.2f %s recorded on %s (principal %.2f / interest %.2f)".formatted(
                        amount, p.getCurrency(), facilityRef, principal, interest),
                Map.of("applicationReference", applicationReference, "facilityRef", facilityRef,
                        "amount", amount, "principal", principal, "interest", interest));
        return saved;
    }

    /** Checker lane: confirms the repayment and books the limit RELEASE for the principal. */
    @Transactional
    public Repayment confirm(Long id, String actor) {
        requireActor(actor);
        roles.require(actor, ProtectedAction.REPAYMENT_CONFIRM);
        Repayment p = get(id);
        if (!"RECORDED".equals(p.getStatus())) {
            throw ApiException.conflict("Repayment is " + p.getStatus());
        }
        if (actor.equals(p.getRecordedBy())) {
            throw ApiException.forbiddenAutonomy(
                    "Repayment confirmer must differ from recorder (" + actor + ")");
        }
        String txnRef = "RPMT-" + p.getApplicationReference() + "-" + p.getFacilityRef() + "-" + p.getId();
        bookRelease(p, txnRef, actor);
        p.setStatus("CONFIRMED");
        p.setConfirmedBy(actor);
        p.setConfirmedAt(Instant.now());
        p.setReleaseRef(txnRef);
        Repayment saved = repo.save(p);
        audit.human(actor, "REPAYMENT_CONFIRMED", "Repayment", String.valueOf(id),
                "Confirmed repayment of %.2f %s on %s — principal %.2f released on the limit ledger (%s)"
                        .formatted(p.getAmount(), p.getCurrency(), p.getFacilityRef(),
                                p.getPrincipalComponent(), txnRef),
                Map.of("facilityRef", p.getFacilityRef(), "principal", p.getPrincipalComponent(),
                        "releaseRef", txnRef));
        return saved;
    }

    @Transactional
    public Repayment reject(Long id, String reason, String actor) {
        requireActor(actor);
        roles.require(actor, ProtectedAction.REPAYMENT_REJECT);
        Repayment p = get(id);
        if (!"RECORDED".equals(p.getStatus())) {
            throw ApiException.conflict("Repayment is " + p.getStatus());
        }
        if (actor.equals(p.getRecordedBy())) {
            throw ApiException.forbiddenAutonomy(
                    "The recorder (" + actor + ") cannot reject their own repayment entry");
        }
        p.setStatus("REJECTED");
        p.setRejectedBy(actor);
        p.setRejectedReason(reason);
        p.setRejectedAt(Instant.now());
        Repayment saved = repo.save(p);
        audit.human(actor, "REPAYMENT_REJECTED", "Repayment", String.valueOf(id),
                "Rejected repayment entry on %s — %s".formatted(p.getFacilityRef(), reason),
                Map.of("facilityRef", p.getFacilityRef(), "reason", reason == null ? "" : reason));
        return saved;
    }

    // ============================================================ connector lane (core banking)

    /**
     * Ingests a servicing-system repayment event: idempotent on the envelope key,
     * warnings surfaced (never dropped), and the limit RELEASE booked as a SYSTEM
     * action — a machine feed is the truth, so there is no maker-checker here.
     */
    @Transactional
    public Ingestion.Result ingest(String applicationReference,
                                   Ingestion.Envelope<RawRepaymentEvent> envelope, String actor) {
        String key = envelope.idempotencyKey();
        if (key == null || key.isBlank()) {
            throw ApiException.badRequest("idempotencyKey is required");
        }
        var prior = guard.priorIngestion(SourceSystem.CORE_BANKING, key);
        if (prior.isPresent()) {
            return Ingestion.Result.duplicate(SourceSystem.CORE_BANKING, key, prior.get().getCanonicalRef());
        }
        RawRepaymentEvent raw = envelope.payload();
        if (raw == null || raw.amount() <= 0) {
            throw ApiException.badRequest("payload with a positive amount is required");
        }
        DealEnvelopeDto env = upstream.envelope(applicationReference);
        if (env == null) throw ApiException.notFound("No deal envelope for " + applicationReference);
        FacilityViewDto facility = findFacility(env, raw.facilityRef());
        if (facility == null) {
            throw ApiException.badRequest("Unknown facility '" + raw.facilityRef()
                    + "' on " + applicationReference + " — event NOT ingested");
        }

        List<String> warnings = new ArrayList<>(connector.validate(raw));
        Ingestion.Provenance prov = Ingestion.Provenance.of(
                SourceSystem.CORE_BANKING, envelope.vendor(), key, envelope.payloadVersion());
        Canonical.RepaymentEvent event = connector.map(raw, prov);

        double outstanding = outstandingPrincipal(applicationReference, raw.facilityRef());
        if (event.principalComponent() > outstanding + 0.01) {
            warnings.add("principal %.2f exceeds outstanding %.2f — capped to outstanding"
                    .formatted(event.principalComponent(), outstanding));
        }
        double principal = Math.min(event.principalComponent(), outstanding);

        Repayment p = new Repayment();
        p.setApplicationReference(applicationReference);
        p.setFacilityRef(raw.facilityRef());
        p.setAmount(round2(event.amount()));
        p.setPrincipalComponent(round2(principal));
        p.setInterestComponent(round2(event.amount() - principal));
        p.setCurrency(event.currency() == null
                ? (facility.currency() == null ? "INR" : facility.currency().toUpperCase())
                : event.currency());
        p.setValueDate(event.valueDate());
        p.setSource("CORE_BANKING");
        p.setExternalRef(event.externalRef());
        p.setStatus("RECORDED");
        p.setRecordedBy("core-banking");
        Repayment saved = repo.save(p);

        String txnRef = "RPMT-" + applicationReference + "-" + raw.facilityRef() + "-" + saved.getId();
        bookRelease(saved, txnRef, actor);
        saved.setStatus("CONFIRMED");
        saved.setConfirmedBy("SYSTEM:core-banking");
        saved.setConfirmedAt(Instant.now());
        saved.setReleaseRef(txnRef);
        repo.save(saved);

        guard.record(SourceSystem.CORE_BANKING, key, String.valueOf(saved.getId()),
                "repayment %.2f %s on %s (principal %.2f)".formatted(
                        saved.getAmount(), saved.getCurrency(), saved.getFacilityRef(),
                        saved.getPrincipalComponent()));
        audit.engine("REPAYMENT_INGESTED", "Repayment", String.valueOf(saved.getId()),
                "Core-banking repayment of %.2f %s on %s — principal %.2f released (%s)%s".formatted(
                        saved.getAmount(), saved.getCurrency(), saved.getFacilityRef(),
                        saved.getPrincipalComponent(), txnRef,
                        warnings.isEmpty() ? "" : " (warnings surfaced)"),
                Map.of("facilityRef", saved.getFacilityRef(), "amount", saved.getAmount(),
                        "principal", saved.getPrincipalComponent(),
                        "idempotencyKey", key, "warnings", warnings));
        return Ingestion.Result.accepted(SourceSystem.CORE_BANKING, key, String.valueOf(saved.getId()),
                "repayment ingested — principal %.2f released on the limit ledger"
                        .formatted(saved.getPrincipalComponent()), warnings);
    }

    // ============================================================ reads

    @Transactional(readOnly = true)
    public Repayment get(Long id) {
        return repo.findById(id).orElseThrow(() -> ApiException.notFound("No repayment: " + id));
    }

    @Transactional(readOnly = true)
    public List<Repayment> historyFor(String applicationReference, String facilityRef) {
        return facilityRef == null || facilityRef.isBlank()
                ? repo.findByApplicationReferenceOrderByIdDesc(applicationReference)
                : repo.findByApplicationReferenceAndFacilityRefOrderByIdDesc(applicationReference, facilityRef);
    }

    /**
     * Current outstanding principal on a facility per the decision-service ledger:
     * released draws (net of reversals) minus confirmed principal repayments.
     */
    @Transactional(readOnly = true)
    public double outstandingPrincipal(String applicationReference, String facilityRef) {
        double released = disbursements
                .findByApplicationReferenceAndFacilityRefOrderByDrawdownNoAsc(applicationReference, facilityRef)
                .stream()
                .filter(d -> "RELEASED".equals(d.getStatus()))
                .mapToDouble(Disbursement::getAmount).sum();
        double repaid = repo.findByApplicationReferenceAndFacilityRefAndStatusIn(
                        applicationReference, facilityRef, List.of("CONFIRMED")).stream()
                .mapToDouble(Repayment::getPrincipalComponent).sum();
        return round2(Math.max(0, released - repaid));
    }

    // ============================================================ helpers

    private void bookRelease(Repayment p, String txnRef, String actor) {
        LimitClient.LimitNodeDto node = limits.nodeForFacility(p.getApplicationReference(), p.getFacilityRef());
        if (node == null) {
            throw ApiException.conflict("No limit node for facility " + p.getFacilityRef()
                    + " on " + p.getApplicationReference());
        }
        LimitClient.UtilisationResponseDto response = limits.release(node.cif(), node.reference(),
                p.getPrincipalComponent(), p.getCurrency(), txnRef, actor);
        if (response == null || !response.success()) {
            String reason = response == null ? "no response from limit-service"
                    : (response.results() == null || response.results().isEmpty()
                            ? "no result rows" : response.results().get(0).message());
            throw ApiException.conflict("Limit release failed: " + reason);
        }
    }

    private FacilityViewDto findFacility(DealEnvelopeDto env, String facilityRef) {
        if (env.facilities() == null) return null;
        for (FacilityViewDto f : env.facilities()) {
            if (f.reference() != null && f.reference().equals(facilityRef)) return f;
        }
        return null;
    }

    private static void requireActor(String actor) {
        if (actor == null || actor.isBlank()) {
            throw ApiException.forbiddenAutonomy(
                    "A named human actor (X-Actor header) is required on repayment actions");
        }
    }

    private static double round2(double v) { return Math.round(v * 100.0) / 100.0; }
}
