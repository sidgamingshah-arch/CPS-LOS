package com.helix.decision.entity;

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

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * A generated credit proposal / committee memo (PRD §8). Grounded in platform
 * data — every figure traces to its source; AI never invents numbers. Versioned;
 * each generation creates a new row so the trail is intact (PRD §13).
 */
@Entity
@Table(name = "credit_proposals", indexes = {
        @Index(name = "idx_proposal_app", columnList = "applicationReference")
})
@Getter
@Setter
public class CreditProposal {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 30)
    private String applicationReference;

    @Column(nullable = false)
    private int version;

    @Lob
    @Column(nullable = false, length = 32000)
    private String html;

    @Lob
    @Column(nullable = false, length = 16000)
    private String markdown;

    /** Citations: each ("section" -> [source endpoints]) so any figure is traceable. */
    @Convert(converter = JsonAttributeConverters.MapConverter.class)
    @Column(length = 8000)
    private Map<String, Object> citations;

    @Convert(converter = JsonAttributeConverters.StringListConverter.class)
    @Column(length = 2000)
    private List<String> sections;

    /**
     * The CAM format code the proposal was assembled under (e.g. STANDARD, PROJECT_FINANCE,
     * LRD). Nullable for pre-existing rows; a no-format generate resolves STANDARD. Mapped to a
     * non-reserved column name to dodge any SQLite keyword collision on {@code format}.
     */
    @Column(name = "proposal_format", length = 60)
    private String format;

    private String generatedBy;

    @CreationTimestamp
    @Column(updatable = false)
    private Instant generatedAt;
}
