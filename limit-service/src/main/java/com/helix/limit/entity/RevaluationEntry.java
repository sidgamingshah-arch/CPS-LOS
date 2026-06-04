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

/**
 * A per-node mark-to-market entry captured during an EOD revaluation pass. One row
 * per non-base-currency limit node whose base-currency sanctioned amount moved
 * between the previous and the current FX rate.
 */
@Entity
@Table(name = "limit_revaluations", indexes = {
        @Index(name = "idx_reval_run", columnList = "runId"),
        @Index(name = "idx_reval_node", columnList = "nodeId")
})
@Getter
@Setter
public class RevaluationEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long runId;

    @Column(nullable = false)
    private Long nodeId;

    @Column(nullable = false, length = 40)
    private String code;

    @Column(nullable = false, length = 5)
    private String currency;

    @Column(nullable = false)
    private double sanctionedAmount;

    @Column(nullable = false)
    private double oldBase;

    @Column(nullable = false)
    private double newBase;

    @Column(nullable = false)
    private double delta;

    @Column(nullable = false)
    private double fxRate;
}
