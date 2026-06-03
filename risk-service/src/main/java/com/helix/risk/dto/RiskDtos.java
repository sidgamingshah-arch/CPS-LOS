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
            @NotBlank String role) {
    }

    public record OverrideStats(String segment, long total, long overridden, double overrideRate,
                                boolean exceedsAlertThreshold) {
    }

    public record RiskSummary(String applicationReference, Rating rating, CapitalResult capital,
                              PricingResult pricing) {
    }
}
