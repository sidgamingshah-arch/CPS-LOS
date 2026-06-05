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
 * A macro directional-impact assessment (PRD macro directional impact). Given a
 * macro scenario (rate / GDP / FX / sector shifts) it projects the directional move
 * in PD and the implied rating-notch direction. <b>Advisory and non-binding</b> — it
 * does not rewrite the authoritative rating; it tells the analyst which way and how
 * hard the macro winds are blowing on a name.
 */
@Entity
@Table(name = "macro_impact_assessments", indexes = {
        @Index(name = "idx_macro_app", columnList = "applicationReference")
})
@Getter
@Setter
public class MacroImpactAssessment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String applicationReference;

    @Column(nullable = false)
    private String scenarioName;

    @Column(nullable = false)
    private double baselinePd;
    @Column(nullable = false)
    private double stressedPd;
    @Column(nullable = false)
    private double pdDeltaBps;

    @Column(nullable = false, length = 8)
    private String direction;         // UP | DOWN | STABLE  (UP = worsening / PD higher)

    @Column(nullable = false)
    private double notchEstimate;     // signed: negative = downgrade pressure

    @Column(nullable = false, length = 1200)
    private String rationale;

    @Column(nullable = false)
    private boolean advisory;

    @Convert(converter = JsonAttributeConverters.MapConverter.class)
    @Column(length = 8000)
    private Map<String, Object> contributions;   // factor -> pd multiplier contribution

    @CreationTimestamp
    @Column(updatable = false)
    private Instant createdAt;
}
