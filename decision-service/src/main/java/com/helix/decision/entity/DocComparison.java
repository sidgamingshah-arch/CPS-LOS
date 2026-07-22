package com.helix.decision.entity;

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
 * A deterministic, read-only structured comparison of two versioned artifacts already
 * held in decision-service (CLoM F57 "incremental change / document comparison"). The
 * engine parses each artifact into sections and emits a change table
 * ({@code ADDED / REMOVED / CHANGED / UNCHANGED}) — it never mutates either source.
 *
 * <p>Two artifact kinds are supported:
 * <ul>
 *   <li>{@code PROPOSAL_VERSIONS} — two {@link CreditProposal} versions of one application
 *       ({@code subjectRef} = applicationReference; {@code leftRef}/{@code rightRef} =
 *       version numbers). Sections are the proposal's {@code ## } headings.</li>
 *   <li>{@code DOCUMENT_VERSIONS} — two {@link GeneratedDocument} rows
 *       ({@code leftRef}/{@code rightRef} = document ids). Sections are the document's
 *       clauses.</li>
 * </ul>
 *
 * <p>The comparison is <b>advisory</b> and purely derived: the same two source artifacts
 * always yield a byte-identical {@code diff}. The stored {@code diff} map wraps the change
 * rows under the {@code rows} key (each row is {@code section / changeType / oldValue /
 * newValue}); the per-type counts are stored as explicit columns for cheap listing.
 */
@Entity
@Table(name = "doc_comparisons", indexes = {
        @Index(name = "idx_cmp_ref", columnList = "comparisonRef"),
        @Index(name = "idx_cmp_subject", columnList = "subjectRef")
})
@Getter
@Setter
public class DocComparison {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Stable public identifier — {@code CMP-XXXXXX}. */
    @Column(nullable = false, length = 20, unique = true)
    private String comparisonRef;

    /** PROPOSAL_VERSIONS | DOCUMENT_VERSIONS (see {@link Kind}). */
    @Column(nullable = false, length = 30)
    private String kind;

    /** The subject the two artifacts belong to (e.g. the application reference). */
    @Column(nullable = false, length = 60)
    private String subjectRef;

    /** The left (older / baseline) artifact selector — version number or document id. */
    @Column(nullable = false, length = 60)
    private String leftRef;

    /** The right (newer / revised) artifact selector — version number or document id. */
    @Column(nullable = false, length = 60)
    private String rightRef;

    /** Human label of the left artifact (deterministic, no timestamps). */
    @Column(length = 200)
    private String leftLabel;

    /** Human label of the right artifact (deterministic, no timestamps). */
    @Column(length = 200)
    private String rightLabel;

    @Column(name = "added_count")
    private int added;

    @Column(name = "removed_count")
    private int removed;

    @Column(name = "changed_count")
    private int changed;

    @Column(name = "unchanged_count")
    private int unchanged;

    /** {@code { "rows": [ { section, changeType, oldValue, newValue }, ... ] }}. */
    @Convert(converter = JsonAttributeConverters.MapConverter.class)
    @Column(length = 100000)
    private Map<String, Object> diff;

    /** Always true — a comparison is derived/advisory and never authoritative. */
    @Column(nullable = false)
    private boolean advisory = true;

    @Column(length = 80)
    private String createdBy;

    @CreationTimestamp
    @Column(updatable = false)
    private Instant createdAt;

    /** Supported artifact kinds. */
    public enum Kind {
        PROPOSAL_VERSIONS, DOCUMENT_VERSIONS;

        public static Kind parse(String value) {
            if (value == null) throw new IllegalArgumentException("kind is required");
            return Kind.valueOf(value.trim().toUpperCase());
        }
    }

    /** Change taxonomy for a single section row. */
    public enum ChangeType {
        ADDED, REMOVED, CHANGED, UNCHANGED
    }
}
