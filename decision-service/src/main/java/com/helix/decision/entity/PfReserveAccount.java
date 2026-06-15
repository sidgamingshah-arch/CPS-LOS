package com.helix.decision.entity;

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
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;

/**
 * A project-finance reserve account — DSRA (debt-service reserve), TRA
 * (trust-and-retention) or similar. The facility documents require it to be funded
 * to a minimum before drawdowns may proceed; the PF drawdown gate checks every
 * reserve is at-or-above its required balance.
 */
@Entity
@Table(name = "pf_reserve_accounts", indexes = {
        @Index(name = "idx_pfres_app", columnList = "applicationReference")
})
@Getter
@Setter
public class PfReserveAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 60)
    private String applicationReference;

    /** DSRA · TRA · MMRA (major-maintenance reserve) · other free-form. */
    @Column(nullable = false, length = 20)
    private String accountType;

    @Column(nullable = false, precision = 22, scale = 2)
    private java.math.BigDecimal requiredAmount = com.helix.common.money.Money.ZERO;

    /** Running balance — fund/withdraw accumulate here, so it's a BigDecimal ledger field. */
    @Column(nullable = false, precision = 22, scale = 2)
    private java.math.BigDecimal currentBalance = com.helix.common.money.Money.ZERO;

    @Column(nullable = false, length = 8)
    private String currency;

    /** FUNDED when currentBalance >= requiredAmount, else SHORTFALL. */
    @Column(nullable = false, length = 20)
    private String status = "SHORTFALL";

    @Column(length = 80) private String lastActionBy;
    private Instant lastActionAt;

    /** Who last put money IN — a withdrawal by the same person is an SoD violation. */
    @Column(length = 80) private String lastFundedBy;

    @CreationTimestamp
    private Instant createdAt;

    @UpdateTimestamp
    private Instant updatedAt;
}
