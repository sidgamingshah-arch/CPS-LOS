package com.helix.portfolio.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
 * A single escrow transaction — money in ({@code CREDIT}) or out ({@code DEBIT}),
 * optionally tagged to a budget category so it counts toward that category's actuals.
 * Append-only; the deterministic budget-vs-actual read sums these per active category.
 *
 * <p>Column {@code tagged_category} is reserved-word-safe.</p>
 */
@Entity
@Table(name = "escrow_transactions", indexes = {
        @Index(name = "idx_escrow_txn_account", columnList = "accountRef"),
        @Index(name = "idx_escrow_txn_category", columnList = "tagged_category")
})
@Getter
@Setter
public class EscrowTransaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 40)
    private String accountRef;

    @Column(nullable = false)
    private double amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private EscrowDirection direction;

    /** Budget category this transaction is tagged to (reserved-word-safe column); may be null. */
    @Column(name = "tagged_category", length = 80)
    private String taggedCategory;

    private LocalDate valueDate;

    @Column(length = 500)
    private String memo;

    @Column(nullable = false, length = 120)
    private String postedBy;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private Instant createdAt;
}
