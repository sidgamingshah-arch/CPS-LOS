package com.helix.risk.entity;

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
 * A multi-year financial projection for a deal: the resolved PROJECTION_TEMPLATE,
 * the analyst's driver assumptions, and human-confirm state. The projected grid
 * itself is computed deterministically on read (base actuals × drivers × template
 * formulas), not stored. Advisory — projections never move authoritative figures.
 */
@Entity
@Table(name = "projections", indexes = {
        @Index(name = "idx_projection_app", columnList = "applicationReference", unique = true)
})
@Getter
@Setter
public class Projection {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 30)
    private String applicationReference;

    @Column(nullable = false, length = 80)
    private String templateKey;

    private int templateVersion;

    private int horizonYears;

    /** Analyst driver overrides (driver key -> value); merged over the template defaults. */
    @Lob
    @Convert(converter = JsonAttributeConverters.MapConverter.class)
    @Column(length = 4000)
    private Map<String, Object> drivers;

    private boolean advisory = true;

    @Column(nullable = false, length = 20)
    private String status = "DRAFT";   // DRAFT | CONFIRMED

    @Column(length = 80)
    private String confirmedBy;

    private Instant confirmedAt;

    @CreationTimestamp
    @Column(updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    private Instant updatedAt;
}
