package com.helix.origination.entity;

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
 * A GenAI document-intelligence extraction over an uploaded {@link Document} (PRD
 * multilingual extraction). The extraction is an <b>AI suggestion</b>: it carries
 * per-field confidence and source provenance, starts in SUGGESTED, and must be
 * human-confirmed. Confirmation records review accountability but is deliberately
 * <b>not</b> wired into the deterministic figure path — spreads remain human-entered
 * with cell-level provenance, so AI never produces a credit-consequential figure.
 */
@Entity
@Table(name = "doc_extractions", indexes = {
        @Index(name = "idx_extract_doc", columnList = "documentId"),
        @Index(name = "idx_extract_app", columnList = "applicationReference")
})
@Getter
@Setter
public class DocExtraction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long documentId;

    @Column(nullable = false, length = 30)
    private String applicationReference;

    private String classifiedType;
    private String detectedLanguage;     // ISO-ish: en | ar | hi | …
    private String model;                // doc-intel-v1
    private double overallConfidence;

    @Column(nullable = false, length = 16)
    private String status;               // SUGGESTED | CONFIRMED | REJECTED

    @Convert(converter = JsonAttributeConverters.MapConverter.class)
    @Column(length = 8000)
    private Map<String, Object> fields;  // key -> {value, confidence, sourcePage}

    private String reviewedBy;
    private Instant reviewedAt;
    private String reviewNote;

    @CreationTimestamp
    @Column(updatable = false)
    private Instant createdAt;
}
