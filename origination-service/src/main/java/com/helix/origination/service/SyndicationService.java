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
import com.helix.origination.dto.SyndicationDtos.SyndicatedDealSummary;
import com.helix.origination.entity.DealParticipant;
import com.helix.origination.entity.DealStructure;
import com.helix.origination.entity.LoanApplication;
import com.helix.origination.entity.SecondaryTransfer;
import com.helix.origination.entity.SyndicationAllocation;
import com.helix.origination.entity.SyndicationFeedBatch;
import com.helix.origination.entity.SyndicationInvitation;
import com.helix.origination.repo.DealParticipantRepository;
import com.helix.origination.repo.DealStructureRepository;
import com.helix.origination.repo.LoanApplicationRepository;
import com.helix.origination.repo.SecondaryTransferRepository;
import com.helix.origination.repo.SyndicationAllocationRepository;
import com.helix.origination.repo.SyndicationFeedBatchRepository;
import com.helix.origination.repo.SyndicationInvitationRepository;
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
    private final SyndicationInvitationRepository invitations;
    private final SecondaryTransferRepository transfers;
    private final OriginationMasterClient masters;
    private final AuditService audit;
    private final ObjectMapper mapper;

    public SyndicationService(DealStructureRepository structures, DealParticipantRepository participants,
                              LoanApplicationRepository applications, SyndicationAllocationRepository allocations,
                              SyndicationFeedBatchRepository feedBatches,
                              SyndicationInvitationRepository invitations,
                              SecondaryTransferRepository transfers,
                              OriginationMasterClient masters, AuditService audit, ObjectMapper mapper) {
        this.structures = structures;
        this.participants = participants;
        this.applications = applications;
        this.allocations = allocations;
        this.feedBatches = feedBatches;
        this.invitations = invitations;
        this.transfers = transfers;
        this.masters = masters;
        this.audit = audit;
        this.mapper = mapper;
    }

    // ============================================================ syndicated-deal discovery

    /**
     * Every SYNDICATION-structured deal, summarised for a picker (reference, borrower,
     * total captured commitment, lender count). Lets the UI offer ONLY syndicated deals
     * instead of the whole application list. Read-only and fail-soft — a deal whose
     * application row is missing still lists (borrower/currency fall back gracefully).
     */
    @Transactional(readOnly = true)
    public List<SyndicatedDealSummary> syndicatedDeals() {
        List<SyndicatedDealSummary> out = new ArrayList<>();
        for (DealStructure s : structures.findByStructureType("SYNDICATION")) {
            String ref = s.getApplicationReference();
            List<DealParticipant> lenders = lenders(ref);
            double totalCommitment = lenders.stream().mapToDouble(DealParticipant::getCommittedAmount).sum();
            LoanApplication app = applications.findByReference(ref).orElse(null);
            String borrower = app != null ? app.getCounterpartyName() : ref;
            String currency = app != null ? app.getCurrency() : "INR";
            out.add(new SyndicatedDealSummary(ref, borrower, currency, round2(totalCommitment), lenders.size()));
        }
        return out;
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

        // funded-to-date per participant from the allocation ledger (reversed rows excluded)
        Map<Long, Double> fundedByParticipant = new java.util.HashMap<>();
        for (SyndicationAllocation a : allocations.findByApplicationReferenceOrderByIdAsc(reference)) {
            if ("REVERSED".equals(a.getStatus())) continue;
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

    /**
     * Reverses an allocated drawdown — every participant row for the drawdownRef is
     * marked REVERSED (kept for the audit trail, excluded from funded-to-date).
     * Idempotent: already-reversed rows are left alone; unknown drawdownRef is a 404.
     */
    @Transactional
    public AllocationResult reverseAllocation(String reference, String drawdownRef, String actor) {
        List<SyndicationAllocation> rows =
                allocations.findByApplicationReferenceAndDrawdownRefOrderByIdAsc(reference, drawdownRef);
        if (rows.isEmpty()) {
            throw ApiException.notFound("No allocation for drawdown " + drawdownRef + " on " + reference);
        }
        boolean anyFlipped = false;
        for (SyndicationAllocation a : rows) {
            if ("REVERSED".equals(a.getStatus())) continue;
            a.setStatus("REVERSED");
            a.setReversedBy(actor);
            a.setReversedAt(java.time.Instant.now());
            allocations.save(a);
            anyFlipped = true;
        }
        if (anyFlipped) {
            audit.engine("SYNDICATION_ALLOCATION_REVERSED", "Application", reference,
                    "Reversed allocation of drawdown %s across %d lender(s)".formatted(drawdownRef, rows.size()),
                    Map.of("drawdownRef", drawdownRef, "lenders", rows.size()));
        }
        return toResult(reference, drawdownRef, rows.get(0).getDrawdownAmount(), rows, !anyFlipped);
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

    // ============================================================ invitations

    @Transactional
    public SyndicationInvitation invite(String reference, String invitedBank, String invitedBankRef,
                                        double proposedCommitment, String proposedRole, String currency,
                                        String terms, Integer expiresInDays, String actor) {
        DealStructure structure = structures.findByApplicationReference(reference)
                .orElseThrow(() -> ApiException.notFound("No deal structure for " + reference));
        if (!"SYNDICATION".equalsIgnoreCase(structure.getStructureType())) {
            throw ApiException.badRequest(reference + " is not a SYNDICATION deal");
        }
        if (invitedBank == null || invitedBank.isBlank()) {
            throw ApiException.badRequest("invitedBank is required");
        }
        if (proposedCommitment <= 0) {
            throw ApiException.badRequest("proposedCommitment must be positive");
        }
        if (actor == null || actor.isBlank()) {
            throw ApiException.forbiddenAutonomy("A named human actor is required to send an invitation");
        }
        String role = proposedRole == null || proposedRole.isBlank()
                ? "PARTICIPANT_LENDER" : proposedRole.toUpperCase();
        if (!LENDER_ROLES.contains(role)) {
            throw ApiException.badRequest("Invitation role must be LEAD_BANK or PARTICIPANT_LENDER");
        }
        SyndicationInvitation inv = new SyndicationInvitation();
        inv.setApplicationReference(reference);
        inv.setInvitedBank(invitedBank);
        inv.setInvitedBankRef(invitedBankRef);
        inv.setProposedCommitment(round2(proposedCommitment));
        inv.setProposedRole(role);
        inv.setCurrency(currency == null || currency.isBlank()
                ? currencyFor(reference) : currency.toUpperCase());
        inv.setTerms(terms);
        inv.setInvitedBy(actor);
        if (expiresInDays != null && expiresInDays > 0) {
            inv.setExpiresAt(java.time.Instant.now().plus(expiresInDays, java.time.temporal.ChronoUnit.DAYS));
        }
        SyndicationInvitation saved = invitations.save(inv);
        audit.human(actor, "SYNDICATION_INVITED", "SyndicationInvitation", String.valueOf(saved.getId()),
                "Invited %s to syndicate %s for %.2f %s (%s)".formatted(
                        invitedBank, reference, proposedCommitment, inv.getCurrency(), role),
                Map.of("reference", reference, "invitedBank", invitedBank,
                        "commitment", proposedCommitment, "role", role));
        return saved;
    }

    @Transactional
    public SyndicationInvitation acceptInvitation(Long id, String actor) {
        SyndicationInvitation inv = getInvitation(id);
        guardInvitationDecidable(inv, actor);
        // On accept, materialise a DealParticipant for the now-joined lender.
        LoanApplication app = applications.findByReference(inv.getApplicationReference())
                .orElseThrow(() -> ApiException.notFound("No application: " + inv.getApplicationReference()));
        int nextOrdinal = participants.findByApplicationReferenceOrderByOrdinalAsc(
                inv.getApplicationReference()).size();
        DealParticipant p = new DealParticipant();
        p.setApplicationId(app.getId());
        p.setApplicationReference(inv.getApplicationReference());
        p.setRole(inv.getProposedRole());
        p.setName(inv.getInvitedBank());
        p.setExternalRef(inv.getInvitedBankRef());
        p.setCommittedAmount(inv.getProposedCommitment());
        p.setOrdinal(nextOrdinal);
        DealParticipant savedP = participants.save(p);

        inv.setStatus("ACCEPTED");
        inv.setDecidedBy(actor);
        inv.setDecidedAt(java.time.Instant.now());
        inv.setParticipantId(savedP.getId());
        SyndicationInvitation saved = invitations.save(inv);
        audit.human(actor, "SYNDICATION_INVITATION_ACCEPTED", "SyndicationInvitation", String.valueOf(id),
                "Accepted invitation to syndicate %s for %.2f %s — joined as %s".formatted(
                        inv.getApplicationReference(), inv.getProposedCommitment(),
                        inv.getCurrency(), inv.getProposedRole()),
                Map.of("reference", inv.getApplicationReference(),
                        "commitment", inv.getProposedCommitment(),
                        "participantId", savedP.getId()));
        return saved;
    }

    @Transactional
    public SyndicationInvitation declineInvitation(Long id, String reason, String actor) {
        SyndicationInvitation inv = getInvitation(id);
        guardInvitationDecidable(inv, actor);
        inv.setStatus("DECLINED");
        inv.setDecidedBy(actor);
        inv.setDecidedAt(java.time.Instant.now());
        inv.setDeclineReason(reason);
        SyndicationInvitation saved = invitations.save(inv);
        audit.human(actor, "SYNDICATION_INVITATION_DECLINED", "SyndicationInvitation", String.valueOf(id),
                "Declined invitation to %s — %s".formatted(inv.getApplicationReference(),
                        reason == null ? "no reason" : reason),
                Map.of("reference", inv.getApplicationReference(),
                        "reason", reason == null ? "" : reason));
        return saved;
    }

    @Transactional
    public SyndicationInvitation withdrawInvitation(Long id, String reason, String actor) {
        SyndicationInvitation inv = getInvitation(id);
        if (!"SENT".equals(inv.getStatus())) {
            throw ApiException.conflict("Invitation is " + inv.getStatus());
        }
        if (actor == null || !actor.equals(inv.getInvitedBy())) {
            throw ApiException.forbiddenAutonomy(
                    "Only the lead-bank actor who sent the invitation (" + inv.getInvitedBy()
                    + ") may withdraw it");
        }
        inv.setStatus("WITHDRAWN");
        inv.setDecidedBy(actor);
        inv.setDecidedAt(java.time.Instant.now());
        inv.setDeclineReason(reason);
        SyndicationInvitation saved = invitations.save(inv);
        audit.human(actor, "SYNDICATION_INVITATION_WITHDRAWN", "SyndicationInvitation", String.valueOf(id),
                "Withdrew invitation to %s — %s".formatted(inv.getInvitedBank(),
                        reason == null ? "no reason" : reason),
                Map.of("invitedBank", inv.getInvitedBank()));
        return saved;
    }

    @Transactional(readOnly = true)
    public List<SyndicationInvitation> invitationsFor(String reference) {
        return invitations.findByApplicationReferenceOrderByIdDesc(reference);
    }

    private SyndicationInvitation getInvitation(Long id) {
        return invitations.findById(id)
                .orElseThrow(() -> ApiException.notFound("No invitation: " + id));
    }

    private void guardInvitationDecidable(SyndicationInvitation inv, String actor) {
        if (!"SENT".equals(inv.getStatus())) {
            throw ApiException.conflict("Invitation is " + inv.getStatus());
        }
        if (actor == null || actor.isBlank()) {
            throw ApiException.forbiddenAutonomy("A named human actor is required");
        }
        if (actor.equals(inv.getInvitedBy())) {
            throw ApiException.forbiddenAutonomy(
                    "Invitation decision must be made by a different actor than the inviter ("
                    + inv.getInvitedBy() + ")");
        }
        if (inv.getExpiresAt() != null && java.time.Instant.now().isAfter(inv.getExpiresAt())) {
            // Self-heal: mark expired and refuse the decision.
            inv.setStatus("EXPIRED");
            inv.setDecidedAt(java.time.Instant.now());
            invitations.save(inv);
            throw ApiException.conflict("Invitation expired at " + inv.getExpiresAt());
        }
    }

    // ============================================================ secondary transfers

    @Transactional
    public SecondaryTransfer proposeTransfer(String reference, Long fromParticipantId,
                                             String toBank, String toBankRef,
                                             double transferAmount, String currency,
                                             String reason, String actor) {
        DealStructure structure = structures.findByApplicationReference(reference)
                .orElseThrow(() -> ApiException.notFound("No deal structure for " + reference));
        if (!"SYNDICATION".equalsIgnoreCase(structure.getStructureType())) {
            throw ApiException.badRequest(reference + " is not a SYNDICATION deal");
        }
        DealParticipant from = participants.findById(fromParticipantId)
                .filter(p -> reference.equals(p.getApplicationReference()))
                .orElseThrow(() -> ApiException.notFound("No participant " + fromParticipantId + " on " + reference));
        if (!LENDER_ROLES.contains(from.getRole())) {
            throw ApiException.badRequest("Transfer source must be a lender role (got " + from.getRole() + ")");
        }
        if (toBank == null || toBank.isBlank()) {
            throw ApiException.badRequest("toBank is required");
        }
        if (transferAmount <= 0) {
            throw ApiException.badRequest("transferAmount must be positive");
        }
        if (actor == null || actor.isBlank()) {
            throw ApiException.forbiddenAutonomy("A named human actor is required to propose a transfer");
        }
        // The transferable balance is the UNFUNDED commitment — funded historical
        // allocations stay with the original lender. The agent's secondary
        // register tracks the sold-down portion separately, but the live book
        // adjusts on future draws only.
        double funded = allocations.findByApplicationReferenceOrderByIdAsc(reference).stream()
                .filter(a -> !"REVERSED".equals(a.getStatus()))
                .filter(a -> fromParticipantId.equals(a.getParticipantId()))
                .mapToDouble(SyndicationAllocation::getAllocatedAmount).sum();
        double unfunded = from.getCommittedAmount() - funded;
        if (transferAmount > unfunded + 0.01) {
            throw ApiException.badRequest(
                    "Cannot transfer %.2f — only %.2f is unfunded on %s's commitment (funded %.2f of %.2f)"
                            .formatted(transferAmount, unfunded, from.getName(), funded, from.getCommittedAmount()));
        }
        SecondaryTransfer t = new SecondaryTransfer();
        t.setApplicationReference(reference);
        t.setFromParticipantId(fromParticipantId);
        t.setFromName(from.getName());
        t.setToBank(toBank);
        t.setToBankRef(toBankRef);
        t.setTransferAmount(round2(transferAmount));
        t.setCurrency(currency == null || currency.isBlank()
                ? currencyFor(reference) : currency.toUpperCase());
        t.setReason(reason);
        t.setProposedBy(actor);
        SecondaryTransfer saved = transfers.save(t);
        audit.human(actor, "SYNDICATION_TRANSFER_PROPOSED", "SecondaryTransfer", String.valueOf(saved.getId()),
                "%s proposes to sell down %.2f %s of %s commitment to %s".formatted(
                        from.getName(), transferAmount, t.getCurrency(), reference, toBank),
                Map.of("reference", reference, "fromName", from.getName(),
                        "toBank", toBank, "transferAmount", transferAmount));
        return saved;
    }

    @Transactional
    public SecondaryTransfer settleTransfer(Long id, String comment, String actor) {
        SecondaryTransfer t = getTransfer(id);
        if (!"PROPOSED".equals(t.getStatus())) {
            throw ApiException.conflict("Transfer is " + t.getStatus());
        }
        if (actor == null || actor.isBlank() || actor.equals(t.getProposedBy())) {
            throw ApiException.forbiddenAutonomy(
                    "Transfer settlement must be made by an agent actor different from the transferor ("
                    + t.getProposedBy() + ")");
        }
        DealParticipant from = participants.findById(t.getFromParticipantId())
                .orElseThrow(() -> ApiException.notFound("Transferor participant gone"));
        // Re-verify unfunded headroom at settlement time (draws may have happened
        // between propose and settle).
        double funded = allocations.findByApplicationReferenceOrderByIdAsc(t.getApplicationReference()).stream()
                .filter(a -> !"REVERSED".equals(a.getStatus()))
                .filter(a -> t.getFromParticipantId().equals(a.getParticipantId()))
                .mapToDouble(SyndicationAllocation::getAllocatedAmount).sum();
        double unfunded = from.getCommittedAmount() - funded;
        if (t.getTransferAmount() > unfunded + 0.01) {
            throw ApiException.conflict(
                    "Settlement aborted — unfunded commitment is now %.2f, transfer needs %.2f (draws since proposal)"
                            .formatted(unfunded, t.getTransferAmount()));
        }
        // Re-cut commitments.
        from.setCommittedAmount(round2(from.getCommittedAmount() - t.getTransferAmount()));
        participants.save(from);

        // If the transferee is already a participant (by externalRef match if
        // present, else by name), merge into that row; otherwise create a fresh
        // PARTICIPANT_LENDER for them.
        DealParticipant to = participants.findByApplicationReferenceOrderByOrdinalAsc(t.getApplicationReference())
                .stream()
                .filter(p -> LENDER_ROLES.contains(p.getRole()))
                .filter(p -> {
                    if (t.getToBankRef() != null && !t.getToBankRef().isBlank()
                            && t.getToBankRef().equals(p.getExternalRef())) return true;
                    return t.getToBankRef() == null && t.getToBank().equalsIgnoreCase(p.getName());
                })
                .findFirst().orElse(null);
        if (to == null) {
            LoanApplication app = applications.findByReference(t.getApplicationReference())
                    .orElseThrow(() -> ApiException.notFound("No application"));
            int nextOrdinal = participants.findByApplicationReferenceOrderByOrdinalAsc(
                    t.getApplicationReference()).size();
            to = new DealParticipant();
            to.setApplicationId(app.getId());
            to.setApplicationReference(t.getApplicationReference());
            to.setRole("PARTICIPANT_LENDER");
            to.setName(t.getToBank());
            to.setExternalRef(t.getToBankRef());
            to.setCommittedAmount(t.getTransferAmount());
            to.setOrdinal(nextOrdinal);
        } else {
            to.setCommittedAmount(round2(to.getCommittedAmount() + t.getTransferAmount()));
        }
        DealParticipant savedTo = participants.save(to);

        t.setStatus("SETTLED");
        t.setAgentDecidedBy(actor);
        t.setAgentDecidedAt(java.time.Instant.now());
        t.setDecisionComment(comment);
        t.setToParticipantId(savedTo.getId());
        SecondaryTransfer saved = transfers.save(t);
        audit.human(actor, "SYNDICATION_TRANSFER_SETTLED", "SecondaryTransfer", String.valueOf(id),
                "Settled secondary transfer: %s -> %s, %.2f %s (%s -> %s)".formatted(
                        from.getName(), savedTo.getName(), t.getTransferAmount(), t.getCurrency(),
                        round2(from.getCommittedAmount() + t.getTransferAmount()), from.getCommittedAmount()),
                Map.of("reference", t.getApplicationReference(),
                        "fromParticipantId", t.getFromParticipantId(),
                        "toParticipantId", savedTo.getId(),
                        "transferAmount", t.getTransferAmount()));
        return saved;
    }

    @Transactional
    public SecondaryTransfer rejectTransfer(Long id, String reason, String actor) {
        SecondaryTransfer t = getTransfer(id);
        if (!"PROPOSED".equals(t.getStatus())) {
            throw ApiException.conflict("Transfer is " + t.getStatus());
        }
        if (actor == null || actor.isBlank() || actor.equals(t.getProposedBy())) {
            throw ApiException.forbiddenAutonomy(
                    "Transfer rejection must be made by an agent actor different from the transferor");
        }
        t.setStatus("REJECTED");
        t.setAgentDecidedBy(actor);
        t.setAgentDecidedAt(java.time.Instant.now());
        t.setDecisionComment(reason);
        SecondaryTransfer saved = transfers.save(t);
        audit.human(actor, "SYNDICATION_TRANSFER_REJECTED", "SecondaryTransfer", String.valueOf(id),
                "Rejected secondary transfer from %s — %s".formatted(t.getFromName(),
                        reason == null ? "no reason" : reason),
                Map.of("reference", t.getApplicationReference(),
                        "reason", reason == null ? "" : reason));
        return saved;
    }

    @Transactional(readOnly = true)
    public List<SecondaryTransfer> transfersFor(String reference) {
        return transfers.findByApplicationReferenceOrderByIdDesc(reference);
    }

    private SecondaryTransfer getTransfer(Long id) {
        return transfers.findById(id)
                .orElseThrow(() -> ApiException.notFound("No secondary transfer: " + id));
    }
}
