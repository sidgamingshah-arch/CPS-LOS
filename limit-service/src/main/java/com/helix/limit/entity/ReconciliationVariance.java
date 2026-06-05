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
 * A per-node reconciliation variance surfaced by the EOD utilisation pass. For
 * each leaf node the ledger is summed (UTILISE/RELEASE/RESERVE/REVERSAL) and
 * compared to the recorded outstanding / cumulativeDrawn / reserved. For each
 * parent node the children's recorded balances are summed and compared to the
 * parent's. A nonzero delta indicates data drift and the row is captured so the
 * ops desk can investigate.
 */
@Entity
@Table(name = "limit_reconciliation_variances", indexes = {
        @Index(name = "idx_recon_run", columnList = "runId"),
        @Index(name = "idx_recon_node", columnList = "nodeId")
})
@Getter
@Setter
public class ReconciliationVariance {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long runId;

    @Column(nullable = false)
    private Long nodeId;

    @Column(nullable = false, length = 40)
    private String code;

    @Column(nullable = false, length = 20)
    private String scope;            // LEAF | PARENT

    @Column(nullable = false, length = 20)
    private String field;            // outstanding | cumulativeDrawn | reserved

    @Column(nullable = false)
    private double recorded;

    @Column(nullable = false)
    private double computed;

    @Column(nullable = false)
    private double variance;
}
