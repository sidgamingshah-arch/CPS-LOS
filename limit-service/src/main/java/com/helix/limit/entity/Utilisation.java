package com.helix.limit.entity;

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

import com.helix.common.money.Money;

import java.math.BigDecimal;
import java.time.Instant;

/** Immutable utilisation ledger entry from a product processor (PRD Utilisation API). */
@Entity
@Table(name = "utilisations", indexes = {
        @Index(name = "idx_util_node", columnList = "limitNodeId"),
        @Index(name = "idx_util_cif", columnList = "cif"),
        @Index(name = "idx_util_txnref", columnList = "transactionRef")
})
@Getter
@Setter
public class Utilisation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long limitNodeId;

    @Column(nullable = false, length = 30)
    private String cif;

    @Column(nullable = false, length = 20)
    private String action;            // UTILISE | RELEASE | RESERVE | REVERSAL

    @Column(nullable = false, precision = 22, scale = 2)
    private BigDecimal amount = Money.ZERO;
    @Column(nullable = false, length = 5)
    private String currency;
    @Column(precision = 22, scale = 2)
    private BigDecimal baseAmount = Money.ZERO;

    private String productProcessor;
    private String transactionRef;
    private boolean overrideApplied;

    @Column(nullable = false, length = 20)
    private String status;            // CONFIRMED | REJECTED

    private String message;

    @CreationTimestamp
    @Column(updatable = false)
    private Instant createdAt;
}
