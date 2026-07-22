package com.helix.origination.entity;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * A <b>Banking Account Statement Review (ASR)</b> — an account-conduct analysis of a
 * borrower's banking-arrangement statements, captured during origination (CLoM R1-10).
 *
 * <p>The captured monthly {@link BankingAsrLine} rows drive a set of <b>deterministic</b>
 * account-conduct metrics (average balance, peak/avg utilisation, credit/debit summations,
 * cheque returns, min/max balance, …) computed here with <b>no LLM in the figure path</b>.
 * An <i>optional</i> {@code advisorySummary} narrative may be drafted at the AI boundary;
 * it is advisory-only and never mutates any computed metric. A named human confirms the
 * review (DRAFT → CONFIRMED); the record never writes to a limit, exposure, rating or price.</p>
 */
@Entity
@Table(name = "banking_asr", indexes = {
        @Index(name = "idx_asr_ref", columnList = "asrRef", unique = true),
        @Index(name = "idx_asr_app", columnList = "applicationRef"),
        @Index(name = "idx_asr_status", columnList = "status")
})
@Getter
@Setter
public class BankingAsr {

    /** Lifecycle of a banking ASR. Persisted as a String (never an ordinal). */
    public enum Status {
        DRAFT, CONFIRMED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 20, unique = true)
    private String asrRef;                 // ASR-XXXXXX

    @Column(nullable = false, length = 30)
    private String applicationRef;

    @Column(nullable = false)
    private String bankName;

    /** Masked account number as supplied (e.g. XXXXXX1234) — never the full PAN/account. */
    @Column(length = 40)
    private String accountNoMasked;

    @Column(nullable = false, length = 5)
    private String currency;

    @Column(length = 20)
    private String periodFrom;

    @Column(length = 20)
    private String periodTo;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status;

    // ---- deterministic computed metrics (no LLM in this path) ----
    /** Mean across months of the monthly average balance ((opening+closing)/2). */
    private double averageBankBalance;
    /** Highest monthly utilisation (drawn ÷ sanctionedLimit) observed across the period. */
    private double peakUtilisationPct;
    /** Mean across months of monthly utilisation (drawn ÷ sanctionedLimit). */
    private double avgUtilisationPct;
    /** Sum of monthly total credits across the period. */
    private double totalCredits;
    /** Sum of monthly total debits across the period. */
    private double totalDebits;
    /** totalCredits ÷ number of months (average monthly credit summation). */
    private double creditSummationMonthlyAvg;
    /** Sum of inward cheque returns across the period. */
    private double chequeReturnsInward;
    /** Sum of outward cheque returns across the period. */
    private double chequeReturnsOutward;
    /** Lowest in-month balance observed across the period. */
    private double minBalance;
    /** Highest in-month (peak) balance observed across the period. */
    private double maxBalance;
    /** Sum of monthly transaction counts across the period. */
    private int transactionCount;
    /** The sanctioned limit the utilisation figures are computed against. */
    private double sanctionedLimit;

    // ---- optional advisory narrative (AI boundary; never mutates a metric) ----
    @Column(length = 8000)
    private String advisorySummary;

    /** Advisory marker for the (optional) narrative — the metrics above are deterministic. */
    @Column(nullable = false)
    private boolean advisory = true;

    /** The model / template that drafted the advisory summary (nullable until summarised). */
    @Column(length = 60)
    private String summaryModel;

    @Column(nullable = false, length = 60)
    private String createdBy;

    /** The named human who confirmed the review (nullable until CONFIRMED). */
    @Column(length = 60)
    private String confirmedBy;

    private Instant confirmedAt;

    @CreationTimestamp
    @Column(updatable = false)
    private Instant createdAt;

    /** The captured monthly conduct lines that drive the deterministic metrics. */
    @OneToMany(mappedBy = "asr", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @OrderBy("id ASC")
    private List<BankingAsrLine> lines = new ArrayList<>();
}
