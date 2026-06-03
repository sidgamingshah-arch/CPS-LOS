package com.helix.common.ingest;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;

/**
 * Records each accepted ingestion so replays are idempotent (PRD §8). Unique on
 * (source, idempotencyKey): a repeated payload is recognised and not re-applied.
 */
@Entity
@Table(name = "ingestion_records",
        uniqueConstraints = @UniqueConstraint(name = "uq_ingest_source_key", columnNames = {"source", "idempotencyKey"}),
        indexes = @Index(name = "idx_ingest_canonical", columnList = "canonicalRef"))
public class IngestionRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 40)
    private String source;

    @Column(nullable = false, length = 200)
    private String idempotencyKey;

    @Column(length = 120)
    private String canonicalRef;

    @Column(length = 500)
    private String summary;

    @CreationTimestamp
    @Column(updatable = false)
    private Instant receivedAt;

    public Long getId() {
        return id;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public void setIdempotencyKey(String idempotencyKey) {
        this.idempotencyKey = idempotencyKey;
    }

    public String getCanonicalRef() {
        return canonicalRef;
    }

    public void setCanonicalRef(String canonicalRef) {
        this.canonicalRef = canonicalRef;
    }

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }

    public Instant getReceivedAt() {
        return receivedAt;
    }
}
