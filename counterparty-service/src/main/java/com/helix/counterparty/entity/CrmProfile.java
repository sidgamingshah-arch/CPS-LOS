package com.helix.counterparty.entity;

import com.helix.common.util.JsonAttributeConverters;
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
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.List;

/**
 * An inbound CRM account/relationship profile ingested for a counterparty via the canonical
 * connector contract (PRD §8). INPUT / provenance data only — flagged {@code advisory}; it never
 * mutates an authoritative figure. Column names avoid SQLite reserved words.
 */
@Entity
@Table(name = "crm_profiles", indexes = {
        @Index(name = "idx_crm_cp", columnList = "counterpartyId")
})
@Getter
@Setter
public class CrmProfile {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long counterpartyId;

    @Column(length = 80)
    private String crmId;

    private String accountName;

    @Column(length = 120)
    private String relationshipManager;

    @Column(name = "crm_segment", length = 60)
    private String segment;

    @Column(name = "relationship_value")
    private double relationshipValue;

    private String primaryContactName;

    @Column(length = 160)
    private String primaryContactEmail;

    @Convert(converter = JsonAttributeConverters.StringListConverter.class)
    @Column(length = 1000)
    private List<String> productsHeld;

    @Column(length = 60)
    private String lifecycleStage;

    // ---- provenance (figure -> source -> version trace) ----
    @Column(nullable = false, length = 40)
    private String sourceSystem;      // CRM

    @Column(length = 80)
    private String sourceVendor;      // e.g. salesforce / simulated-crm

    @Column(length = 120)
    private String sourceReference;   // idempotency key

    @Column(length = 40)
    private String payloadVersion;

    private Instant retrievedAt;

    /** Ingested external data is advisory INPUT, never an authoritative figure. */
    @Column(nullable = false)
    private boolean advisory = true;

    @CreationTimestamp
    @Column(updatable = false)
    private Instant createdAt;
}
