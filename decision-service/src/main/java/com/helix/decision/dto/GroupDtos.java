package com.helix.decision.dto;

import java.util.List;
import java.util.Map;

/** Read models for the group-decisioning views (advisory, grounded). */
public final class GroupDtos {

    private GroupDtos() {
    }

    public record GroupMemberInsight(
            String counterpartyRef,
            String counterpartyName,
            String segment,
            String recordType,
            String rm,
            String latestApplicationReference,
            String applicationStatus,
            // figures (deterministic, copied verbatim from upstream — never mutated)
            String finalGrade,
            String modelGrade,
            Double pd,
            boolean ratingOverridden,
            boolean ratingConfirmed,
            Double exposure,
            String currency,
            int facilityCount,
            int covenantCount,
            // pricing snapshot (advisory)
            Double recommendedRate,
            Double raroc,
            Double hurdleRaroc,
            boolean belowHurdle) {
    }

    public record GroupInsights(
            String groupReference,
            String groupName,
            String groupRm,
            boolean multiCountry,
            String country,
            int memberCount,
            int obligorCount,
            int membersWithApplication,
            int membersBelowHurdle,
            int membersOverridden,
            // weighted aggregates
            Map<String, Double> totalExposureByCurrency,
            Double weightedAveragePd,
            Double weightedAverageRaroc,
            String lowestGrade,
            String highestGrade,
            // explainability
            List<String> concentrations,    // e.g. "70% in AED", "60% in real-estate"
            List<String> riskCallouts,      // e.g. "1/3 members below hurdle"
            List<GroupMemberInsight> members,
            String narrative,
            boolean advisory) {
    }
}
