package com.helix.portfolio.entity;

import com.helix.common.util.JsonAttributeConverters;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.Map;

/**
 * A generated outbound export batch (PRD downstream ERM/Finance/CPR feeds). Holds the
 * full canonical {@link com.helix.common.export.Export.Envelope} as a JSON payload and
 * is idempotent on {@code idempotencyKey} (destination + feedType + as-of day), so a
 * re-run for the same day returns the existing batch rather than duplicating the feed.
 */
@Entity
@Table(name = "export_batches",
        uniqueConstraints = @UniqueConstraint(name = "uq_export_idem", columnNames = "idempotencyKey"),
        indexes = {
                @Index(name = "idx_export_dest", columnList = "destination"),
                @Index(name = "idx_export_key", columnList = "idempotencyKey")
        })
@Getter
@Setter
public class ExportBatch {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 30)
    private String destination;          // ERM | FINANCE_GL | CPR | REGULATORY

    @Column(nullable = false, length = 60)
    private String feedType;

    @Column(nullable = false, length = 160)
    private String idempotencyKey;

    @Column(nullable = false, length = 20)
    private String asOf;

    @Column(nullable = false)
    private int recordCount;

    @Column(nullable = false, length = 20)
    private String status;               // GENERATED | DELIVERED

    private String generatedBy;

    @Convert(converter = JsonAttributeConverters.MapConverter.class)
    @Column(length = 200000)
    private Map<String, Object> envelope;   // the full canonical Export.Envelope

    @CreationTimestamp
    @Column(updatable = false)
    private Instant createdAt;
}
