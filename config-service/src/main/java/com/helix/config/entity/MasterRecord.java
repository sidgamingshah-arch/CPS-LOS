package com.helix.config.entity;

import com.helix.common.util.JsonAttributeConverters;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.Map;

/**
 * A generic, front-end-maintainable master record under maker-checker control.
 *
 * <p>One engine backs every "X Master" in the platform — deduplication rules,
 * negative list, inactivity thresholds, email templates, facility / collateral /
 * covenant libraries, RAROC masters, valuation & charge agencies, EWS triggers,
 * industry benchmarks, document/TnC templates, etc. The master is identified by a
 * free-form {@code masterType}; each record is a {@code recordKey} with a JSON
 * {@code payload}. Changes flow maker → checker (segregation of duties) and are
 * versioned & effective on approval.</p>
 */
@Entity
@Table(name = "master_records", indexes = {
        @Index(name = "idx_master_type_status", columnList = "masterType,status"),
        @Index(name = "idx_master_lookup", columnList = "masterType,recordKey,status")
})
@Getter
@Setter
public class MasterRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 60)
    private String masterType;          // DEDUP_RULES, NEGATIVE_LIST, COVENANT_LIBRARY, FACILITY_MASTER, …

    @Column(nullable = false, length = 120)
    private String recordKey;           // unique business key within the master type

    @Column(length = 20)
    private String jurisdiction;        // optional, for region-specific masters

    @Lob
    @Convert(converter = JsonAttributeConverters.MapConverter.class)
    @Column(nullable = false, length = 16000)
    private Map<String, Object> payload;

    @Column(nullable = false, length = 20)
    private String status;              // PENDING_APPROVAL | ACTIVE | REJECTED | INACTIVE

    @Column(nullable = false)
    private int version;

    // ---- maker-checker (segregation of duties) ----
    private String maker;
    private Instant makerAt;
    private String checker;
    private Instant checkerAt;
    private String comment;

    @CreationTimestamp
    @Column(updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    private Instant updatedAt;
}
