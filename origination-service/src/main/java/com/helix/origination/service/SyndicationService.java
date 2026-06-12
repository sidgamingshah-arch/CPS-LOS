package com.helix.origination.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.helix.common.audit.AuditService;
import com.helix.common.export.DownstreamSystem;
import com.helix.common.export.Export;
import com.helix.common.web.ApiException;
import com.helix.origination.client.OriginationMasterClient;
import com.helix.origination.dto.SyndicationDtos.AllocationLine;
import com.helix.origination.dto.SyndicationDtos.AllocationResult;
import com.helix.origination.dto.SyndicationDtos.FeeBreakdown;
import com.helix.origination.dto.SyndicationDtos.LenderLine;
import com.helix.origination.dto.SyndicationDtos.SyndicateBook;
import com.helix.origination.entity.DealParticipant;
import com.helix.origination.entity.DealStructure;
import com.helix.origination.entity.LoanApplication;
import com.helix.origination.entity.SyndicationAllocation;
import com.helix.origination.entity.SyndicationFeedBatch;
import com.helix.origination.repo.DealParticipantRepository;
import com.helix.origination.repo.DealStructureRepository;
import com.helix.origination.repo.LoanApplicationRepository;
import com.helix.origination.repo.SyndicationAllocationRepository;
import com.helix.origination.repo.SyndicationFeedBatchRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Syndication agency engine — the mechanics on top of the syndicate participation
 * capture (the lead/participant {@link DealParticipant} rows set on the deal
 * structure). It adds the three things a real agent bank does that capture alone
 * does not:
 *
 * <ol>
 *   <li><b>Fee waterfall</b> — arrangement / underwriting / agency / participation
 *       fees split per the {@code SYNDICATION_FEE_MASTER} (jurisdiction-overridable).</li>
 *   <li><b>Agency reconciliation</b> — a funded drawdown is allocated pro-rata to
 *       each lender's share and persisted (idempotent on the drawdown ref), so the
 *       book tracks funded-to-date per participant, not just commitments.</li>
 *   <li><b>Participant statements</b> — a canonical downstream feed (one
 *       {@code Export.SyndicationParticipantLine} per lender) for the participant banks.</li>
 * </ol>
 */
@Service
public class SyndicationService {

    private static final Set<String> LENDER_ROLES = Set.of("LEAD_BANK", "PARTICIPANT_LENDER");

    private final DealStructureRepository structures;
    private final DealParticipantRepository participants;
    private final LoanApplicationRepository applications;
    private final SyndicationAllocationRepository allocations;
    private final SyndicationFeedBatchRepository feedBatches;
    private final OriginationMasterClient masters;
    private final AuditService audit;
    private final ObjectMapper mapper;

    public SyndicationService(DealStructureRepository structures, DealParticipantRepository participants,
                              LoanApplicationRepository applications, SyndicationAllocationRepository allocations,
                              SyndicationFeedBatchRepository feedBatches,
                              OriginationMasterClient masters, AuditService audit, ObjectMapper mapper) {
        this.structures = structures;
        this.participants = participants;
        this.applications = applications;
        this.allocations = allocations;
        this.feedBatches = feedBatches;
        this.masters = masters;
        this.audit = audit;
        this.mapper = mapper;
    }

    // ============================================================ syndicate book

    @Transactional(readOnly = true)
    public SyndicateBook book(String reference) {
        DealStructure structure = structures.findByApplicationReference(reference)
                .orElseThrow(() -> ApiException.notFound("No deal structure for " + reference));
        if (!"SYNDICATION".equalsIgnoreCase(structure.getStructureType())) {
            throw ApiException.badRequest(reference + " is not a SYNDICATION deal (structure="
                    + structure.getStructureType() + ")");
        }
        List<DealParticipant> lenders = lenders(reference);
        if (lenders.isEmpty()) {
            throw ApiException.badRequest("No syndicate lenders captured for " + reference
                    + " — set the deal structure participants first");
        }
        String currency = currencyFor(reference);
        double totalCommitment = lenders.stream().mapToDouble(DealParticipant::getCommittedAmount).sum();
        Map<String, Object> fees = masters.syndicationFees(jurisdictionFor(reference));

        // funded-to-date per participant from the allocation ledger
        Map<Long, Double> fundedByParticipant = new java.util.HashMap<>();
        for (SyndicationAllocation a : allocations.findByApplicationReferenceOrderByIdAsc(reference)) {
            fundedByParticipant.merge(a.getParticipantId(), a.getAllocatedAmount(), Double::sum);
        }

        List<LenderLine> lines = new ArrayList<>();
        double feeArr = 0, feeUw = 0, feeAg = 0, feePart = 0, totalFunded = 0;
        for (DealParticipant p : lenders) {
            double share = totalCommitment > 0 ? p.getCommittedAmount() / totalCommitment : 0.0;
            double funded = fundedByParticipant.getOrDefault(p.getId(), 0.0);
            totalFunded += funded;
            FeeBreakdown fb = feesFor(p, share, totalCommitment, fees);
            feeArr += fb.arrangementFee(); feeUw += fb.underwritingFee();
            feeAg += fb.agencyFee(); feePart += fb.participationFee();
            lines.add(new LenderLine(p.getId(), p.getName(), p.getExternalRef(), p.getRole(),
                    round2(p.getCommittedAmount()), round4(share), round2(funded),
                    round2(p.getCommittedAmount() - funded), fb));
        }
        FeeBreakdown feeTotals = new FeeBreakdown(round2(feeArr), round2(feeUw), round2(feeAg),
                round2(feePart), round2(feeArr + feeUw + feeAg + feePart));
        double facilityAmount = totalCommitment;  // syndicated size = sum of lender commitments
        boolean fullySubscribed = totalCommitment > 0;
        return new SyndicateBook(reference, currency, round2(facilityAmount), round2(totalCommitment),
                round2(totalFunded), fullySubscribed, lines, feeTotals);
    }

    // ============================================================ fee waterfall

    private FeeBreakdown feesFor(DealParticipant p, double share, double totalCommitment, Map<String, Object> fees) {
        boolean isLead = "LEAD_BANK".equalsIgnoreCase(p.getRole());
        double arrangementBps = num(fees.get("arrangementFeeBps"), 75);
        double underwritingBps = num(fees.get("underwritingFeeBps"), 25);
        double agencyBps = num(fees.get("agencyFeeBps"), 10);
        double participationBps = num(fees.get("participationFeeBps"), 30);

        // Arrangement + underwriting + agency accrue to the lead/agent (it arranged,
        // underwrote and administers); participation fee accrues to every lender on
        // its own committed share.
        double arrangement = isLead ? totalCommitment * arrangementBps / 10_000.0 : 0.0;
        double underwriting = isLead ? totalCommitment * underwritingBps / 10_000.0 : 0.0;
        double agency = isLead ? totalCommitment * agencyBps / 10_000.0 : 0.0;
        double participation = p.getCommittedAmount() * participationBps / 10_000.0;
        return new FeeBreakdown(round2(arrangement), round2(underwriting), round2(agency),
                round2(participation), round2(arrangement + underwriting + agency + participation));
    }

    // ============================================================ agency reconciliation

    /**
     * Allocates a funded drawdown pro-rata to the syndicate. Idempotent on
     * {@code drawdownRef}: a repeat call returns the existing allocation (so the
     * disbursement-release path can call this safely on retry).
     */
    @Transactional
    public AllocationResult allocate(String reference, String drawdownRef, double amount,
                                     String currency, String actor) {
        List<DealParticipant> lenders = lenders(reference);
        if (lenders.isEmpty()) {
            throw ApiException.badRequest("No syndicate lenders for " + reference);
        }
        List<SyndicationAllocation> existing =
                allocations.findByApplicationReferenceAndDrawdownRefOrderByIdAsc(reference, drawdownRef);
        if (!existing.isEmpty()) {
            return toResult(reference, drawdownRef, amount, existing, true);
        }
        String ccy = currency == null || currency.isBlank() ? currencyFor(reference) : currency.toUpperCase();
        double totalCommitment = lenders.stream().mapToDouble(DealParticipant::getCommittedAmount).sum();
        if (totalCommitment <= 0) {
            throw ApiException.badRequest("Syndicate has zero total commitment for " + reference);
        }
        List<SyndicationAllocation> saved = new ArrayList<>();
        for (DealParticipant p : lenders) {
            double share = p.getCommittedAmount() / totalCommitment;
            SyndicationAllocation a = new SyndicationAllocation();
            a.setApplicationReference(reference);
            a.setDrawdownRef(drawdownRef);
            a.setParticipantId(p.getId());
            a.setParticipantName(p.getName());
            a.setRole(p.getRole());
            a.setSharePct(round4(share));
            a.setDrawdownAmount(round2(amount));
            a.setAllocatedAmount(round2(amount * share));
            a.setCurrency(ccy);
            saved.add(allocations.save(a));
        }
        audit.engine("SYNDICATION_DRAWDOWN_ALLOCATED", "Application", reference,
                "Allocated drawdown %s of %.2f %s pro-rata across %d lenders".formatted(
                        drawdownRef, amount, ccy, lenders.size()),
                Map.of("drawdownRef", drawdownRef, "amount", amount, "lenders", lenders.size()));
        return toResult(reference, drawdownRef, amount, saved, false);
    }

    private AllocationResult toResult(String reference, String drawdownRef, double amount,
                                      List<SyndicationAllocation> rows, boolean reused) {
        List<AllocationLine> lines = new ArrayList<>();
        double allocated = 0;
        for (SyndicationAllocation a : rows) {
            allocated += a.getAllocatedAmount();
            lines.add(new AllocationLine(a.getParticipantId(), a.getParticipantName(), a.getRole(),
                    a.getSharePct(), a.getAllocatedAmount(), a.getCurrency()));
        }
        return new AllocationResult(reference, drawdownRef, round2(amount), round2(allocated), reused, lines);
    }

    // ============================================================ participant feed

    /**
     * Canonical downstream feed — one statement line per participating lender,
     * persisted as a {@link SyndicationFeedBatch} so the platform has the same
     * idempotent-batch shape as every other downstream feed (ERM / Finance-GL /
     * CPR). Re-running on the same as-of day returns the existing batch envelope.
     */
    @Transactional
    public Export.Envelope<Export.SyndicationParticipantLine> participantFeed(String reference, String actor) {
        String asOf = LocalDate.now().toString();
        String idempotencyKey = "SYND-" + reference + "-" + asOf;
        SyndicationFeedBatch existing = feedBatches.findByIdempotencyKey(idempotencyKey).orElse(null);
        if (existing != null) {
            return decode(existing);
        }
        SyndicateBook b = book(reference);
        List<Export.SyndicationParticipantLine> records = new ArrayList<>();
        for (LenderLine l : b.lenders()) {
            records.add(new Export.SyndicationParticipantLine(reference, l.externalRef(), l.name(),
                    l.role(), l.commitment(), l.sharePct(), l.fundedToDate(), l.undrawn(),
                    l.fees().totalFee(), b.currency()));
        }
        Export.Envelope<Export.SyndicationParticipantLine> env = Export.Envelope.of(
                DownstreamSystem.SYNDICATION, "PARTICIPANT_STATEMENT", idempotencyKey, "v1", records);

        SyndicationFeedBatch batch = new SyndicationFeedBatch();
        batch.setApplicationReference(reference);
        batch.setDestination(env.destination().name());
        batch.setFeedType(env.feedType());
        batch.setIdempotencyKey(idempotencyKey);
        batch.setAsOf(asOf);
        batch.setRecordCount(env.recordCount());
        batch.setStatus("GENERATED");
        batch.setGeneratedBy(actor);
        batch.setEnvelope(mapper.convertValue(env, new TypeReference<Map<String, Object>>() {}));
        SyndicationFeedBatch saved = feedBatches.save(batch);

        audit.engine("EXPORT_GENERATED", "SyndicationFeedBatch", String.valueOf(saved.getId()),
                "%s %s feed — %d lender(s) for %s".formatted(env.destination(), env.feedType(),
                        env.recordCount(), reference),
                Map.of("destination", env.destination().name(), "feedType", env.feedType(),
                        "records", env.recordCount(), "idempotencyKey", idempotencyKey,
                        "applicationReference", reference));
        return env;
    }

    @Transactional(readOnly = true)
    public List<SyndicationFeedBatch> feedBatchesFor(String reference) {
        return feedBatches.findByApplicationReferenceOrderByIdDesc(reference);
    }

    @SuppressWarnings("unchecked")
    private Export.Envelope<Export.SyndicationParticipantLine> decode(SyndicationFeedBatch batch) {
        Map<String, Object> raw = batch.getEnvelope();
        return mapper.convertValue(raw,
                new TypeReference<Export.Envelope<Export.SyndicationParticipantLine>>() {});
    }

    @Transactional(readOnly = true)
    public List<SyndicationAllocation> allocationLedger(String reference) {
        return allocations.findByApplicationReferenceOrderByIdAsc(reference);
    }

    // ============================================================ helpers

    private List<DealParticipant> lenders(String reference) {
        return participants.findByApplicationReferenceOrderByOrdinalAsc(reference).stream()
                .filter(p -> p.getRole() != null && LENDER_ROLES.contains(p.getRole().toUpperCase()))
                .toList();
    }

    private String currencyFor(String reference) {
        return applications.findByReference(reference).map(LoanApplication::getCurrency).orElse("INR");
    }

    private String jurisdictionFor(String reference) {
        return applications.findByReference(reference).map(LoanApplication::getJurisdiction).orElse(null);
    }

    private static double num(Object o, double dflt) {
        if (o instanceof Number n) return n.doubleValue();
        if (o instanceof String s) try { return Double.parseDouble(s); } catch (Exception ignored) { }
        return dflt;
    }

    private static double round2(double v) { return Math.round(v * 100.0) / 100.0; }
    private static double round4(double v) { return Math.round(v * 10_000.0) / 10_000.0; }
}
