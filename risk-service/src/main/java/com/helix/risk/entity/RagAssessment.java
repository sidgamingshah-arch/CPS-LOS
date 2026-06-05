package com.helix.risk.entity;

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
import java.util.Map;

/**
 * A statistical Red/Amber/Green risk assessment (PRD ML/statistical RAG scoring).
 * This is an <b>advisory, non-binding overlay</b> — it never replaces the authoritative
 * deterministic PD/LGD/EAD rating. It blends the spread ratios and the current grade
 * into a transparent 0–100 score with a per-factor contribution breakdown so a credit
 * officer can see why a name is flagged.
 */
@Entity
@Table(name = "rag_assessments", indexes = {
        @Index(name = "idx_rag_app", columnList = "applicationReference")
})
@Getter
@Setter
public class RagAssessment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String applicationReference;

    @Column(nullable = false, length = 40)
    private String method;            // STATISTICAL_RAG

    @Column(nullable = false)
    private double score;             // 0..100 (higher = stronger)

    @Column(nullable = false, length = 6)
    private String band;              // RED | AMBER | GREEN

    private String gradeSnapshot;     // authoritative grade at assessment time
    private double pdSnapshot;

    @Column(nullable = false)
    private boolean advisory;         // always true — non-binding

    @Convert(converter = JsonAttributeConverters.MapConverter.class)
    @Column(length = 8000)
    private Map<String, Object> factors;   // [{key, value, subScore, weight, contribution}, ...]

    @CreationTimestamp
    @Column(updatable = false)
    private Instant createdAt;
}
