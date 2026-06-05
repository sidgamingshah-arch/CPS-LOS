package com.helix.decision.entity;

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

import java.time.Instant;

/** Per-period covenant observation (PRD §7 covenant rule, §11 monitoring). */
@Entity
@Table(name = "covenant_tests", indexes = {
        @Index(name = "idx_covtest_app", columnList = "applicationReference"),
        @Index(name = "idx_covtest_covenant", columnList = "covenantId")
})
@Getter
@Setter
public class CovenantTest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long covenantId;

    @Column(nullable = false, length = 30)
    private String applicationReference;

    @Column(nullable = false, length = 40)
    private String metric;

    @Column(nullable = false, length = 5)
    private String operator;

    @Column(nullable = false)
    private double threshold;

    @Column(nullable = false)
    private double observed;

    @Column(nullable = false)
    private boolean passed;

    private String source;

    @Column(nullable = false, length = 20)
    private String breachSeverity;

    @CreationTimestamp
    @Column(updatable = false)
    private Instant testedAt;
}
