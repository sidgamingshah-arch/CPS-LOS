package com.helix.risk.dto;

import com.helix.risk.entity.CapitalResult;
import com.helix.risk.entity.PricingResult;
import com.helix.risk.entity.Rating;
import jakarta.validation.constraints.NotBlank;

import java.util.List;

public final class RiskDtos {

    private RiskDtos() {
    }

    /** Controlled reason codes for rating overrides (PRD §5, US-5.2). */
    public static final List<String> REASON_CODES = List.of(
            "POST_BALANCE_SHEET_EVENT", "MANAGEMENT_QUALITY", "GROUP_SUPPORT",
            "SECTOR_OUTLOOK", "DATA_QUALITY", "COLLATERAL_STRENGTH", "OTHER");

    public record OverrideRatingRequest(
            @NotBlank String proposedGrade,
            @NotBlank String reasonCode,
            String note,
            // Legacy advisory field — retained only for payload back-compat. The server resolves the
            // override-notch authority from the AUTHENTICATED actor's ACTOR_ROLE roles (never this body
            // claim), so this value is IGNORED. Optional; may be omitted by new callers.
            String role) {
    }

    public record OverrideStats(String segment, long total, long overridden, double overrideRate,
                                boolean exceedsAlertThreshold) {
    }

    public record RiskSummary(String applicationReference, Rating rating, CapitalResult capital,
                              PricingResult pricing) {
    }

    /**
     * Hypothetical scored-rating parameters for the read-only SCORING_APPROVAL_POLICY simulate
     * endpoint (powers the Approval-Rules matrix "simulate routing" panel). All fields optional —
     * absent numerics default to 0 and {@code overridden} to false when the routing is evaluated.
     * Nothing here is persisted; no rating is read or mutated.
     */
    public record ScoringApprovalSimulateRequest(
            Double exposure, String grade, Integer overrideNotches, Boolean overridden,
            String segment, String jurisdiction, String scoreBand) {
    }
}
