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
 * Obligor/facility rating with PD/LGD/EAD (PRD §5). Carries model vs final grade,
 * override metadata and per-factor contributions. Rating is decision-support:
 * the analyst proposes and an approver confirms; every override is logged and
 * feeds the model-monitoring override-rate signal.
 */
@Entity
@Table(name = "ratings", indexes = {
        @Index(name = "idx_rating_app", columnList = "applicationReference"),
        @Index(name = "idx_rating_segment", columnList = "segment")
})
@Getter
@Setter
public class Rating {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 30)
    private String applicationReference;

    @Column(nullable = false, length = 40)
    private String segment;

    @Column(nullable = false)
    private double modelScore;         // 0..100

    @Column(nullable = false, length = 5)
    private String modelGrade;

    @Column(nullable = false, length = 5)
    private String finalGrade;

    @Column(nullable = false)
    private double pd;

    @Column(nullable = false)
    private double lgd;

    @Column(nullable = false)
    private double ead;

    // ---- override metadata (PRD §5, US-5.2) ----
    private boolean overridden;
    private int overrideNotches;
    private String reasonCode;
    private String overrideNote;
    private String overriddenBy;
    private boolean escalated;

    // ---- confirmation gate ----
    private boolean confirmed;
    private String confirmedBy;
    private Instant confirmedAt;

    @Convert(converter = JsonAttributeConverters.MapConverter.class)
    @Column(length = 8000)
    private Map<String, Object> scoreBreakdown;

    @CreationTimestamp
    @Column(updatable = false)
    private Instant createdAt;
}
