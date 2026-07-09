package com.helix.risk.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;

/**
 * One scoring-model instance per application: which MODEL_DEFINITION was resolved
 * (key + version pinned), and the advisory composite it produced from the captured
 * answers. Advisory throughout — never mutates the authoritative grade.
 */
@Entity
@Table(name = "model_instances", indexes = {
        @Index(name = "idx_model_instance_app", columnList = "applicationReference", unique = true)
})
@Getter
@Setter
public class ModelInstance {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 30)
    private String applicationReference;

    @Column(nullable = false, length = 80)
    private String modelKey;

    private int modelVersion;

    @Column(length = 20)
    private String jurisdiction;

    @Column(length = 40)
    private String segment;

    @Column(length = 40)
    private String sector;

    /** Advisory composite (0-100) and band, recomputed deterministically from answers. */
    private double compositeScore;

    @Column(length = 12)
    private String compositeBand;

    private boolean advisory = true;

    @Column(nullable = false, length = 20)
    private String status = "DRAFT";    // DRAFT | CONFIRMED

    @Column(length = 80)
    private String confirmedBy;

    private Instant confirmedAt;

    @CreationTimestamp
    @Column(updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    private Instant updatedAt;
}
