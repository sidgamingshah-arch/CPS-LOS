package com.helix.origination.api;

import com.helix.common.export.Export;
import com.helix.origination.dto.SyndicationDtos.AllocateRequest;
import com.helix.origination.dto.SyndicationDtos.AllocationResult;
import com.helix.origination.dto.SyndicationDtos.CreateImRequest;
import com.helix.origination.dto.SyndicationDtos.ImSectionRequest;
import com.helix.origination.dto.SyndicationDtos.SyndicateBook;
import com.helix.origination.dto.SyndicationDtos.SyndicatedDealSummary;
import com.helix.origination.entity.InformationMemorandum;
import com.helix.origination.entity.SecondaryTransfer;
import com.helix.origination.entity.SyndicationAllocation;
import com.helix.origination.entity.SyndicationFeedBatch;
import com.helix.origination.entity.SyndicationInvitation;
import com.helix.origination.service.InformationMemorandumService;
import com.helix.origination.service.SyndicationService;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Syndication agency API — the fee waterfall, agency reconciliation and participant
 * feed that sit on top of the syndicate participation capture (deal structure).
 */
@RestController
@RequestMapping("/api/syndication")
public class SyndicationController {

    private final SyndicationService syndication;
    private final InformationMemorandumService memoranda;

    public SyndicationController(SyndicationService syndication, InformationMemorandumService memoranda) {
        this.syndication = syndication;
        this.memoranda = memoranda;
    }

    /**
     * Every SYNDICATION-structured deal, summarised — lets a picker list ONLY syndicated
     * deals (reference, borrower, total commitment, #lenders) instead of the whole app list.
     */
    @GetMapping("/deals")
    public List<SyndicatedDealSummary> syndicatedDeals() {
        return syndication.syndicatedDeals();
    }

    /** Full syndicate book — lenders, shares, fee waterfall, funded-to-date. */
    @GetMapping("/{reference}/book")
    public SyndicateBook book(@PathVariable String reference) {
        return syndication.book(reference);
    }

    /** Agency reconciliation: allocate a funded drawdown pro-rata across lenders. */
    @PostMapping("/{reference}/allocate")
    public AllocationResult allocate(@PathVariable String reference,
                                     @Valid @RequestBody AllocateRequest req,
                                     @RequestHeader(value = "X-Actor", defaultValue = "agency.desk") String actor) {
        return syndication.allocate(reference, req.drawdownRef(), req.amount(), req.currency(), actor);
    }

    @GetMapping("/{reference}/allocations")
    public List<SyndicationAllocation> allocations(@PathVariable String reference) {
        return syndication.allocationLedger(reference);
    }

    /** Reverse an allocated drawdown (idempotent) — rows marked REVERSED, funded-to-date drops. */
    @PostMapping("/{reference}/allocations/reverse")
    public AllocationResult reverseAllocation(@PathVariable String reference,
                                              @RequestBody java.util.Map<String, String> req,
                                              @RequestHeader(value = "X-Actor", defaultValue = "agency.desk") String actor) {
        String drawdownRef = req.get("drawdownRef");
        if (drawdownRef == null || drawdownRef.isBlank()) {
            throw com.helix.common.web.ApiException.badRequest("drawdownRef is required");
        }
        return syndication.reverseAllocation(reference, drawdownRef, actor);
    }

    /** Canonical downstream participant-statement feed — persisted as a SyndicationFeedBatch. */
    @GetMapping("/{reference}/feed")
    public Export.Envelope<Export.SyndicationParticipantLine> feed(@PathVariable String reference,
                                                                    @RequestHeader(value = "X-Actor",
                                                                            defaultValue = "agency.desk") String actor) {
        return syndication.participantFeed(reference, actor);
    }

    /** History of every persisted participant-feed batch for a deal. */
    @GetMapping("/{reference}/feed/batches")
    public List<SyndicationFeedBatch> feedBatches(@PathVariable String reference) {
        return syndication.feedBatchesFor(reference);
    }

    // ============================================================ invitations

    public record InviteRequest(@NotBlank String invitedBank, String invitedBankRef,
                                @Positive double proposedCommitment, String proposedRole,
                                String currency, String terms, Integer expiresInDays) { }

    public record DeclineRequest(@NotBlank String reason) { }

    @PostMapping("/{reference}/invitations")
    public SyndicationInvitation invite(@PathVariable String reference,
                                        @Valid @RequestBody InviteRequest req,
                                        @RequestHeader("X-Actor") String actor) {
        return syndication.invite(reference, req.invitedBank(), req.invitedBankRef(),
                req.proposedCommitment(), req.proposedRole(), req.currency(),
                req.terms(), req.expiresInDays(), actor);
    }

    @PostMapping("/invitations/{id}/accept")
    public SyndicationInvitation acceptInvitation(@PathVariable Long id,
                                                  @RequestHeader("X-Actor") String actor) {
        return syndication.acceptInvitation(id, actor);
    }

    @PostMapping("/invitations/{id}/decline")
    public SyndicationInvitation declineInvitation(@PathVariable Long id,
                                                   @Valid @RequestBody DeclineRequest req,
                                                   @RequestHeader("X-Actor") String actor) {
        return syndication.declineInvitation(id, req.reason(), actor);
    }

    @PostMapping("/invitations/{id}/withdraw")
    public SyndicationInvitation withdrawInvitation(@PathVariable Long id,
                                                    @RequestBody(required = false) DeclineRequest req,
                                                    @RequestHeader("X-Actor") String actor) {
        return syndication.withdrawInvitation(id, req == null ? null : req.reason(), actor);
    }

    @GetMapping("/{reference}/invitations")
    public List<SyndicationInvitation> invitations(@PathVariable String reference) {
        return syndication.invitationsFor(reference);
    }

    // ============================================================ secondary transfers

    public record TransferRequest(@Positive Long fromParticipantId,
                                  @NotBlank String toBank, String toBankRef,
                                  @Positive double transferAmount, String currency,
                                  String reason) { }

    public record DecideRequest(String comment) { }

    @PostMapping("/{reference}/transfers")
    public SecondaryTransfer proposeTransfer(@PathVariable String reference,
                                             @Valid @RequestBody TransferRequest req,
                                             @RequestHeader("X-Actor") String actor) {
        return syndication.proposeTransfer(reference, req.fromParticipantId(),
                req.toBank(), req.toBankRef(), req.transferAmount(), req.currency(),
                req.reason(), actor);
    }

    @PostMapping("/transfers/{id}/settle")
    public SecondaryTransfer settleTransfer(@PathVariable Long id,
                                            @RequestBody(required = false) DecideRequest req,
                                            @RequestHeader("X-Actor") String actor) {
        return syndication.settleTransfer(id, req == null ? null : req.comment(), actor);
    }

    @PostMapping("/transfers/{id}/reject")
    public SecondaryTransfer rejectTransfer(@PathVariable Long id,
                                            @Valid @RequestBody DeclineRequest req,
                                            @RequestHeader("X-Actor") String actor) {
        return syndication.rejectTransfer(id, req.reason(), actor);
    }

    @GetMapping("/{reference}/transfers")
    public List<SecondaryTransfer> transfers(@PathVariable String reference) {
        return syndication.transfersFor(reference);
    }

    // ============================================================ information memorandum

    /** Create a DRAFT IM for a syndication deal, seeded with the standard grounded sections. */
    @PostMapping("/{reference}/im")
    public InformationMemorandum createIm(@PathVariable String reference,
                                          @RequestBody(required = false) CreateImRequest req,
                                          @RequestHeader("X-Actor") String actor) {
        return memoranda.create(reference, req == null ? null : req.title(), actor);
    }

    /** List every IM / version for a deal (newest version first). */
    @GetMapping("/{reference}/im")
    public List<InformationMemorandum> memorandaFor(@PathVariable String reference) {
        return memoranda.listForDeal(reference);
    }

    /** Fetch a single IM by numeric id. */
    @GetMapping("/im/{id}")
    public InformationMemorandum getIm(@PathVariable Long id) {
        return memoranda.get(id);
    }

    /** Upsert one section (DRAFT / CIRCULATED only). */
    @PostMapping("/im/{id}/section")
    public InformationMemorandum upsertImSection(@PathVariable Long id,
                                                 @Valid @RequestBody ImSectionRequest req,
                                                 @RequestHeader("X-Actor") String actor) {
        return memoranda.upsertSection(id, req.key(), req.content(), actor);
    }

    /** DRAFT -> CIRCULATED. */
    @PostMapping("/im/{id}/circulate")
    public InformationMemorandum circulateIm(@PathVariable Long id,
                                             @RequestHeader("X-Actor") String actor) {
        return memoranda.circulate(id, actor);
    }

    /** CIRCULATED -> FINAL. SoD: the finaliser must differ from the drafter (403 otherwise). */
    @PostMapping("/im/{id}/finalise")
    public InformationMemorandum finaliseIm(@PathVariable Long id,
                                            @RequestHeader("X-Actor") String actor) {
        return memoranda.finalise(id, actor);
    }

    /** Withdraw an IM from any live state. */
    @PostMapping("/im/{id}/withdraw")
    public InformationMemorandum withdrawIm(@PathVariable Long id,
                                            @RequestHeader("X-Actor") String actor) {
        return memoranda.withdraw(id, actor);
    }

    /** Append-only re-draft: clone a FINAL / WITHDRAWN IM into a fresh DRAFT at version+1. */
    @PostMapping("/im/{id}/redraft")
    public InformationMemorandum redraftIm(@PathVariable Long id,
                                           @RequestHeader("X-Actor") String actor) {
        return memoranda.redraft(id, actor);
    }
}
