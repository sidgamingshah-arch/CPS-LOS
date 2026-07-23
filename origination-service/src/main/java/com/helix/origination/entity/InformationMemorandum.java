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
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.Map;

/**
 * A syndication Information Memorandum (IM) — the circulated deal document a lead
 * arranger produces to invite and inform participant lenders (CLoM gap #80 /
 * R3-07). It is a <b>versioned DOCUMENT artifact only</b>: its sections are seeded
 * deterministically from existing deal data (application, deal structure, the
 * syndicate book) and edited by named humans. It NEVER mutates any authoritative
 * figure — allocations, participant commitments, fees, ratings and pricing are
 * untouched by every IM transition.
 *
 * <p>Lifecycle: DRAFT → CIRCULATED → FINAL, with WITHDRAWN as an off-ramp from any
 * live state. Versions are append-only: finalising pins the version, and re-drafting
 * a pinned IM clones it into a fresh DRAFT at {@code version + 1} (the source is
 * left byte-identical via {@link #supersedesImRef}). Finalisation is SoD-gated —
 * the finaliser must differ from the {@link #createdBy}.</p>
 */
@Entity
@Table(name = "information_memoranda", indexes = {
        @Index(name = "idx_im_app", columnList = "applicationReference"),
        @Index(name = "idx_im_ref", columnList = "imRef", unique = true),
        @Index(name = "idx_im_status", columnList = "status")
})
@Getter
@Setter
public class InformationMemorandum {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Human-readable IM reference, e.g. {@code IM-APP-000123-V1}. Unique. */
    @Column(nullable = false, length = 60)
    private String imRef;

    /** The origination application reference this IM documents. */
    @Column(nullable = false, length = 30)
    private String applicationReference;

    /** The syndication deal reference (mirrors the application reference — the deal is
     *  keyed by the application under {@code /api/syndication/{reference}}). */
    @Column(nullable = false, length = 30)
    private String syndicationRef;

    /** 1-based version; append-only. A re-draft clones into {@code version + 1}. */
    @Column(nullable = false)
    private int version;

    @Column(nullable = false, length = 200)
    private String title;

    /** DRAFT · CIRCULATED · FINAL · WITHDRAWN */
    @Column(nullable = false, length = 20)
    private String status = "DRAFT";

    /**
     * Section body: key -> {@code {title, content, order, source}}. Seeded with the
     * standard IM sections grounded from deal data; upsertable while DRAFT/CIRCULATED.
     */
    @Convert(converter = JsonAttributeConverters.MapConverter.class)
    @Column(length = 16000)
    private Map<String, Object> sections;

    /** imRef of the IM this one was re-drafted from (version lineage); null for the first. */
    @Column(length = 60)
    private String supersedesImRef;

    @Column(nullable = false, length = 80)
    private String createdBy;

    @CreationTimestamp
    @Column(updatable = false)
    private Instant createdAt;

    @Column(length = 80) private String circulatedBy;
    private Instant circulatedAt;

    @Column(length = 80) private String finalisedBy;
    private Instant finalisedAt;

    @Column(length = 80) private String withdrawnBy;
    private Instant withdrawnAt;

    @UpdateTimestamp
    private Instant updatedAt;
}
