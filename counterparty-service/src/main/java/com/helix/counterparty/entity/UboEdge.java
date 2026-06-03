package com.helix.counterparty.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/** A directed ownership edge: {@code parent} owns {@code child} by {@code ownershipPct} (0..1). */
@Entity
@Table(name = "ubo_edges", indexes = {
        @Index(name = "idx_ubo_edge_cp", columnList = "counterpartyId")
})
@Getter
@Setter
public class UboEdge {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long counterpartyId;

    @Column(nullable = false, length = 60)
    private String parentKey;

    @Column(nullable = false, length = 60)
    private String childKey;

    @Column(nullable = false)
    private double ownershipPct;       // 0..1
}
