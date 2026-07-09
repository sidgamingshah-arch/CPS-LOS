package com.helix.decision.api;

import com.helix.decision.dto.GroupDtos.GroupInsights;
import com.helix.decision.entity.CreditProposal;
import com.helix.decision.service.CreditProposalService;
import com.helix.decision.service.GroupInsightsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Group-level decisioning endpoints — insights rollup and combined credit
 * proposal across every member of a borrower group. Both are advisory:
 * member-level grades, capital and pricing are never mutated by these calls.
 */
@RestController
@RequestMapping("/api/decisions/groups")
public class GroupDecisionController {

    private final GroupInsightsService insights;
    private final CreditProposalService proposals;

    public GroupDecisionController(GroupInsightsService insights, CreditProposalService proposals) {
        this.insights = insights;
        this.proposals = proposals;
    }

    @GetMapping("/{groupReference}/insights")
    public GroupInsights getInsights(@PathVariable String groupReference,
                                     @RequestHeader(value = "X-Actor", defaultValue = "credit.ops") String actor) {
        return insights.insights(groupReference, actor);
    }

    @PostMapping("/{groupReference}/combined-proposal/generate")
    public CreditProposal generateCombined(@PathVariable String groupReference,
                                           @RequestHeader(value = "X-Actor", defaultValue = "analyst.user") String actor) {
        return proposals.generateForGroup(groupReference, actor);
    }

    @GetMapping("/{groupReference}/combined-proposal")
    public CreditProposal latestCombined(@PathVariable String groupReference) {
        return proposals.latest("GRP:" + groupReference);
    }

    @GetMapping("/{groupReference}/combined-proposal/versions")
    public List<CreditProposal> combinedVersions(@PathVariable String groupReference) {
        return proposals.versions("GRP:" + groupReference);
    }
}
