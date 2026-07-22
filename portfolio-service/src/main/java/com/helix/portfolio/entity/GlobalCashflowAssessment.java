package com.helix.portfolio.entity;

import com.helix.common.util.JsonAttributeConverters.StringListConverter;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A global / combined cash-flow (relationship consolidated debt-service) assessment for a
 * borrower group. It consolidates each member's latest CONFIRMED spread figures (revenue, an
 * EBITDA proxy, CFO, and total debt service = interest expense + current-portion LTD) into a
 * combined coverage view with a per-member contribution breakdown.
 *
 * <p><b>Invariant</b>: this is a pure read-side consolidation. It NEVER writes to any member's
 * authoritative spread / rating / exposure — it only persists its own advisory assessment row
 * and an audit event. Member figures are byte-identical before and after (the e2e asserts it).</p>
 */
@Entity
@Table(name = "global_cashflow_assessments", indexes = {
        @Index(name = "uq_gcf_ref", columnList = "gcfRef", unique = true),
        @Index(name = "idx_gcf_group", columnList = "groupReference")
})
@Getter
@Setter
public class GlobalCashflowAssessment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Human-readable reference, generated {@code GCF-XXXXXX}. */
    @Column(nullable = false, length = 40)
    private String gcfRef;

    @Column(nullable = false, length = 60)
    private String groupReference;

    /** Group display name (best-effort from counterparty-service; may be null). */
    @Column(length = 240)
    private String groupName;

    /**
     * Reporting currency of the combined figures. The first included member's currency; set to
     * {@code MIXED} when members report in different currencies (the sums are still emitted, but
     * this flags that a straight sum crosses currencies and should be read with care).
     */
    @Column(length = 16)
    private String currency;

    /** Per-member contribution list: {@code {ref, name, revenue, ebitda, cfo, debtService, dscr}}. */
    @Convert(converter = MemberContributionListConverter.class)
    @Column(length = 8000)
    private List<Map<String, Object>> members = new ArrayList<>();

    /** Number of tagged members considered vs those actually included (with a confirmed spread). */
    @Column(nullable = false)
    private int membersConsidered;

    @Column(nullable = false)
    private int membersIncluded;

    /** Members that could not be consolidated (no live application / no confirmed spread / unreadable). */
    @Convert(converter = StringListConverter.class)
    @Column(length = 4000)
    private List<String> warnings = new ArrayList<>();

    @Column(nullable = false)
    private double combinedRevenue;

    @Column(nullable = false)
    private double combinedEbitda;

    @Column(nullable = false)
    private double combinedCfo;

    @Column(nullable = false)
    private double combinedDebtService;

    /** Combined DSCR = combinedCfo / combinedDebtService (0.0 when no debt service). */
    @Column(nullable = false)
    private double combinedDscr;

    /** Always advisory — a consolidated coverage view, never an authoritative figure. */
    @Column(nullable = false)
    private boolean advisory = true;

    @Column(nullable = false, length = 120)
    private String createdBy;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private Instant createdAt;
}
