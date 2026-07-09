package com.helix.limit.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

import java.util.List;
import java.util.Map;

public final class Dtos {

    private Dtos() {
    }

    public record CreateRootRequest(
            @NotBlank String cif, String applicationRef, Long groupId,
            @NotBlank String code, @Positive double sanctionedAmount, @NotBlank String currency,
            Integer tenorMonths, String segment, String sector, String country, boolean fungible) {
    }

    public record AddChildRequest(
            @NotBlank String code, String productType, String classification, boolean revolving,
            @Positive double sanctionedAmount, @NotBlank String currency, Integer tenorMonths,
            String seniority, boolean fungible, String interchangeableGroup) {
    }

    public record NodeView(Long id, String reference, Long parentId, Long rootId, int level, String code,
                           String productType, boolean revolving, double sanctioned, String currency,
                           double baseAmount, double outstanding, double reserved, double available,
                           boolean fungible, String interchangeableGroup, String status, Integer tenorMonths,
                           String expiryDate, String seniority) {
    }

    public record TreeView(String cif, String baseCurrency, double totalSanctionedBase, double totalOutstandingBase,
                           double totalAvailableBase, List<NodeView> nodes, List<RollupGroup> interchangeabilityGroups) {
    }

    public record RollupGroup(String groupKey, double combinedCap, double combinedOutstanding, List<String> members) {
    }

    // ---- transaction (product-processor) APIs ----

    public record ValidationCheck(String name, boolean pass, String detail) {
    }

    public record ValidationResult(boolean success, String lineId, String message, List<ValidationCheck> checks,
                                   double available, String currency, List<String> terms) {
    }

    public record UtilisationAction(@NotBlank String lineId, @NotBlank String action,
                                    @Positive double amount, String currency, String transactionRef) {
    }

    public record UtilisationRequest(@NotBlank String cif, List<UtilisationAction> actions,
                                     String productProcessor, boolean overrideFlag) {
    }

    public record ActionResult(String lineId, String action, boolean success, String message,
                               double newOutstanding, double newAvailable) {
    }

    public record UtilisationResponse(String cif, boolean success, List<ActionResult> results) {
    }

    public record ExtendRequest(@NotBlank String expiryDate) {
    }

    public record FreezeRequest(String reason) {
    }

    public record ApplicationStatusResult(String applicationRef, String targetStatus,
                                          int affectedCount, int totalNodes, List<String> affectedRefs) {
    }

    public record ExposureCheckResult(boolean withinNorms, List<ValidationCheck> checks, Map<String, Object> norms) {
    }
}
