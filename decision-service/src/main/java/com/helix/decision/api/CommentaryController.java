package com.helix.decision.api;

import com.helix.decision.dto.CommentaryDtos.DraftRequest;
import com.helix.decision.dto.CommentaryDtos.EditRequest;
import com.helix.decision.dto.CommentaryDtos.ReviewRequest;
import com.helix.decision.entity.ProposalCommentary;
import com.helix.decision.service.CommentaryService;
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
 * AI narrative commentary for credit-proposal sections. Each draft is advisory,
 * grounded, with provenance to the underlying facts; confirmation records human
 * accountability and never touches the figure path.
 */
@RestController
@RequestMapping("/api/commentary")
public class CommentaryController {

    private final CommentaryService commentary;

    public CommentaryController(CommentaryService commentary) {
        this.commentary = commentary;
    }

    @PostMapping("/applications/{reference}/draft")
    public ProposalCommentary draft(@PathVariable String reference, @RequestBody DraftRequest req,
                                    @RequestHeader(value = "X-Actor", defaultValue = "analyst.user") String actor) {
        return commentary.draft(reference, req.section(), req.hint(), actor);
    }

    @GetMapping("/applications/{reference}")
    public List<ProposalCommentary> list(@PathVariable String reference,
                                         @RequestParam(required = false) String section) {
        return commentary.list(reference, section);
    }

    @GetMapping("/{id}")
    public ProposalCommentary get(@PathVariable Long id) {
        return commentary.get(id);
    }

    @PostMapping("/{id}/review")
    public ProposalCommentary review(@PathVariable Long id, @RequestBody ReviewRequest req,
                                     @RequestHeader(value = "X-Actor", defaultValue = "credit.officer") String actor) {
        return commentary.review(id, req.approve(), req.note(), actor);
    }

    @PostMapping("/{id}/edit")
    public ProposalCommentary edit(@PathVariable Long id, @RequestBody EditRequest req,
                                   @RequestHeader(value = "X-Actor", defaultValue = "analyst.user") String actor) {
        return commentary.edit(id, req.narrative(), actor);
    }
}
