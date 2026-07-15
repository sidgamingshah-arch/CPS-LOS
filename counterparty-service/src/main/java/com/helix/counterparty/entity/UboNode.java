package com.helix.counterparty.entity;

import com.helix.common.util.EncryptedStringConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * A node in the beneficial-ownership graph (PRD §1, US-1.1). PERSON nodes whose
 * computed effective ownership meets the threshold are flagged as UBOs.
 */
@Entity
@Table(name = "ubo_nodes", indexes = {
        @Index(name = "idx_ubo_node_cp", columnList = "counterpartyId")
})
@Getter
@Setter
public class UboNode {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long counterpartyId;

    @Column(nullable = false, length = 60)
    private String nodeKey;            // stable key within the structure, e.g. "P1", "HOLDCO"

    // Beneficial-owner / party PII. Ownership maths + review routing use nodeKey/edges,
    // never the display name; not queried/ordered — safe to encrypt at rest.
    @Convert(converter = EncryptedStringConverter.class)
    @Column(nullable = false)
    private String name;

    @Column(nullable = false, length = 20)
    private String nodeType;           // PERSON | ENTITY | ROOT (the counterparty itself)

    private String country;

    /** Computed effective ownership of the counterparty (0..1), populated by the resolver. */
    private double effectiveOwnership;

    /** Extraction/identity confidence (0..1). Below the review threshold routes to a human. */
    private double confidence = 1.0;

    private boolean ubo;
    private boolean needsReview;
}
