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
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * An AI-drafted narrative paragraph for a credit-proposal section (PRD AI commentary).
 * Always advisory, grounded in the deal envelope + rating + ratios. Each draft carries
 * its sources (provenance) so the reviewer can tie any sentence back to a source-of-
 * record value. CONFIRMED records human accountability — never auto-issued.
 */
@Entity
@Table(name = "proposal_commentary", indexes = {
        @Index(name = "idx_pcomm_app", columnList = "applicationReference"),
        @Index(name = "idx_pcomm_section", columnList = "section")
})
@Getter
@Setter
public class ProposalCommentary {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 30)
    private String applicationReference;

    @Column(nullable = false, length = 40)
    private String section;          // industry_outlook | management_quality | financial_commentary
                                     // | structure_commentary | risk_commentary

    @Lob
    @Column(nullable = false, length = 8000)
    private String narrative;

    @Convert(converter = JsonAttributeConverters.StringListConverter.class)
    @Column(length = 4000)
    private List<String> bulletPoints;

    @Convert(converter = JsonAttributeConverters.MapConverter.class)
    @Column(length = 8000)
    private Map<String, Object> sources;   // section-specific facts used to draft the narrative

    @Column(nullable = false)
    private double confidence;

    @Column(nullable = false)
    private boolean advisory;

    @Column(nullable = false, length = 16)
    private String status;           // DRAFT | CONFIRMED | REJECTED

    private String draftedBy;
    private String reviewedBy;
    private Instant reviewedAt;
    private String reviewNote;

    @CreationTimestamp
    @Column(updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    private Instant updatedAt;
}
