package com.helix.origination.entity;

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

/**
 * An ingested document with AI classification (PRD §3, US-3.1). High-confidence
 * classifications auto-route; low-confidence ones are flagged for human review.
 */
@Entity
@Table(name = "documents", indexes = {
        @Index(name = "idx_doc_application", columnList = "applicationId")
})
@Getter
@Setter
public class Document {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long applicationId;

    @Column(nullable = false)
    private String fileName;

    @Column(length = 40)
    private String declaredType;

    @Column(nullable = false, length = 40)
    private String classifiedType;     // Enums.DocumentType name

    @Column(nullable = false)
    private double classificationConfidence;

    private boolean needsReview;

    private boolean verified;
    private String verifiedBy;

    @CreationTimestamp
    @Column(updatable = false)
    private Instant uploadedAt;
}
