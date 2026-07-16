package com.helix.portfolio.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.time.LocalDate;

/**
 * An append-only, versioned budget line for one category on an escrow account. A new
 * version for a category NEVER overwrites its predecessor — it is inserted with an
 * incremented {@code versionNo} and becomes the single {@code active} pointer for that
 * category; the prior version is flipped to {@code active=false} but preserved as history.
 *
 * <p>The deterministic budget-vs-actual read baselines actuals against the currently
 * active line per category. Column names are reserved-word-safe ({@code budget_category}).</p>
 */
@Entity
@Table(name = "escrow_budget_lines", indexes = {
        @Index(name = "idx_escrow_budget_account", columnList = "accountRef"),
        @Index(name = "idx_escrow_budget_active", columnList = "accountRef,active")
})
@Getter
@Setter
public class EscrowBudgetLine {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 40)
    private String accountRef;

    /** Budget category (reserved-word-safe column). */
    @Column(name = "budget_category", nullable = false, length = 80)
    private String category;

    @Column(nullable = false)
    private double budgetedAmount;

    /** 1-based version; each new line for a category increments it. */
    @Column(nullable = false)
    private int versionNo;

    /** The single active pointer per category — true for the latest version only. */
    @Column(nullable = false)
    private boolean active;

    /** When this budget baseline takes effect (business date). */
    private LocalDate effectiveFrom;

    @Column(length = 500)
    private String note;

    @Column(nullable = false, length = 120)
    private String createdBy;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private Instant createdAt;
}
