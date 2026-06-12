package com.helix.origination.entity;

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
 * Persisted snapshot of the canonical syndication participant feed for an as-of
 * day — symmetric with portfolio-service's {@code ExportBatch}. Idempotent on
 * {@code idempotencyKey} ({@code SYND-<ref>-<asOf>}), so a repeat call on the
 * same day returns the existing batch rather than emitting a duplicate statement.
 */
@Entity
@Table(name = "syndication_feed_batches",
        uniqueConstraints = @UniqueConstraint(name = "uq_syndfeed_idem", columnNames = "idempotencyKey"),
        indexes = {
                @Index(name = "idx_syndfeed_app", columnList = "applicationReference"),
                @Index(name = "idx_syndfeed_key", columnList = "idempotencyKey")
        })
@Getter
@Setter
public class SyndicationFeedBatch {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 60)
    private String applicationReference;

    @Column(nullable = false, length = 30)
    private String destination;

    @Column(nullable = false, length = 60)
    private String feedType;

    @Column(nullable = false, length = 160)
    private String idempotencyKey;

    @Column(nullable = false, length = 20)
    private String asOf;

    @Column(nullable = false)
    private int recordCount;

    @Column(nullable = false, length = 20)
    private String status = "GENERATED";

    private String generatedBy;

    @Convert(converter = JsonAttributeConverters.MapConverter.class)
    @Column(length = 200000)
    private Map<String, Object> envelope;

    @CreationTimestamp
    @Column(updatable = false)
    private Instant createdAt;
}
