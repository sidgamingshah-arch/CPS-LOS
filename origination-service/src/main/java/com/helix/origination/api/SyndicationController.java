package com.helix.origination.api;

import com.helix.common.export.Export;
import com.helix.origination.dto.SyndicationDtos.AllocateRequest;
import com.helix.origination.dto.SyndicationDtos.AllocationResult;
import com.helix.origination.dto.SyndicationDtos.SyndicateBook;
import com.helix.origination.entity.SyndicationAllocation;
import com.helix.origination.entity.SyndicationFeedBatch;
import com.helix.origination.service.SyndicationService;
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

    public SyndicationController(SyndicationService syndication) {
        this.syndication = syndication;
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
}
