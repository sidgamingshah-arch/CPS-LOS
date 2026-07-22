package com.helix.portfolio.entity;

import jakarta.persistence.Column;
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
import java.time.LocalDate;

/**
 * A light, manual tickler / exception item raised against a subject (a deal or obligor).
 * It complements the read-only aggregated rollup (open covenant / MER / CAD / limit / EWS
 * items surfaced from their owning services) with a human-owned follow-up that lives in
 * portfolio-service itself.
 *
 * <p>State machine: OPEN -&gt; IN_PROGRESS (assigned) -&gt; RESOLVED. Resolution enforces
 * segregation of duties — the resolver must differ from the assigned owner.
 */
@Entity
@Table(name = "ticklers", indexes = {
        @Index(name = "idx_tkl_ref", columnList = "ticklerRef", unique = true),
        @Index(name = "idx_tkl_subject", columnList = "subjectRef"),
        @Index(name = "idx_tkl_owner", columnList = "owner"),
        @Index(name = "idx_tkl_status", columnList = "status")
})
@Getter
@Setter
public class Tickler {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 30)
    private String ticklerRef;      // TKL-*

    @Column(nullable = false, length = 40)
    private String subjectRef;      // deal / obligor reference this tickler tracks

    @Column(nullable = false)
    private String title;

    @Lob
    private String description;

    private String owner;           // accountable actor (nullable until assigned)

    private LocalDate dueAt;

    @Column(nullable = false, length = 10)
    private String priority = "MEDIUM";   // HIGH | MEDIUM | LOW

    @Column(nullable = false, length = 20)
    private String status = "OPEN";       // OPEN | IN_PROGRESS | RESOLVED

    private String resolvedBy;
    @Lob
    private String resolvedNote;
    private Instant resolvedAt;

    @Column(nullable = false)
    private String createdBy;

    @CreationTimestamp
    @Column(updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    private Instant updatedAt;
}
