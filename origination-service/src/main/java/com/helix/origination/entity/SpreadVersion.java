package com.helix.origination.entity;

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

import java.time.Instant;

/**
 * One entry in the append-only financial-spread version timeline: who uploaded
 * which spread, when, and the full analysis as it looked at that moment. Pure
 * history — the live spread tables ({@link FinancialPeriod}/{@link SpreadCell}),
 * the confirm-gate on {@link LoanApplication#isSpreadConfirmed()} and the rating
 * read path are never derived from these rows. Snapshots are immutable once
 * written; the only later mutation is the confirm stamp on the latest version.
 */
@Entity
@Table(name = "spread_versions", indexes = {
        @Index(name = "idx_spreadver_app", columnList = "applicationId")
})
@Getter
@Setter
public class SpreadVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long applicationId;

    /** 1-based, increments per application. */
    @Column(nullable = false)
    private int versionNo;

    @Column(nullable = false)
    private String createdBy;

    @CreationTimestamp
    @Column(updatable = false)
    private Instant createdAt;

    /** MANUAL | DOC_INTEL | RESUBMISSION. */
    @Column(nullable = false, length = 20)
    private String source;

    /** Optional analyst note supplied with the upload. */
    private String note;

    /** Stamped when the analyst confirms the spread while this is the latest version. */
    private boolean confirmed;
    private String confirmedBy;
    private Instant confirmedAt;

    /** The FULL spread analysis (periods, cells incl. provenance, ratios, trends) as JSON. */
    @Lob
    @Column(length = 200000)
    private String snapshot;
}
