package com.helix.portfolio.dto;

import com.helix.portfolio.entity.EscrowAccount;
import com.helix.portfolio.entity.EscrowBudgetLine;
import com.helix.portfolio.entity.EscrowTransaction;

import java.util.List;

/**
 * Request / response records for the escrow monitoring surface. All figures are
 * deterministic aggregations of the recorded budget lines and tagged transactions —
 * nothing here mutates an authoritative ECL / exposure / limit value.
 */
public final class EscrowDtos {

    private EscrowDtos() {
    }

    // ---- requests ----

    public record CreateAccountRequest(String subjectType, String subjectRef, String purpose,
                                       String currency, Double openingBalance) {
    }

    public record BudgetLineRequest(String category, Double budgetedAmount, String effectiveFrom, String note) {
    }

    public record TransactionRequest(Double amount, String direction, String category,
                                     String valueDate, String memo) {
    }

    // ---- responses ----

    /** One category's deterministic budget-vs-actual line with its RAG band. */
    public record CategoryStatus(String category, int budgetVersion, double budgetedAmount,
                                 double credited, double debited, double actual, double variance,
                                 Double utilisationPct, String rag, int transactionCount) {
    }

    /**
     * The full budget-vs-actual summary for an account. {@code overallRag} is the worst
     * category band (RED &gt; AMBER &gt; GREEN). {@code thresholdSource} is MASTER or
     * FALLBACK for auditable provenance of the amber/red bands.
     */
    public record BudgetVsActual(String escrowRef, String currency, double amberPct, double redPct,
                                 String thresholdSource, String overallRag,
                                 double totalBudgeted, double totalActual,
                                 List<CategoryStatus> categories) {
    }

    /** Account + its active budget lines + recent transactions — the read model for one escrow. */
    public record AccountView(EscrowAccount account, List<EscrowBudgetLine> activeBudgetLines,
                              List<EscrowTransaction> transactions) {
    }
}
