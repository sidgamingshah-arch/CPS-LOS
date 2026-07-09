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

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * An AI-drafted Client Planning Template (PRD §1 / §2, "automated CPT
 * generation" + "relationship summary" + "peer analysis" + "wallet sizing" +
 * "region-specific industry insights"). One CPT per counterparty per planning
 * cycle, versioned. Advisory and never auto-approved: an RM confirms or
 * rejects it; the deterministic figure path (rating / capital / pricing) is
 * not mutated by this module.
 */
@Entity
@Table(name = "client_planning_templates", indexes = {
        @Index(name = "idx_cpt_cp", columnList = "counterpartyReference")
})
@Getter
@Setter
public class ClientPlanningTemplate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 30)
    private String counterpartyReference;

    @Column(nullable = false)
    private int version;

    @Column(nullable = false)
    private String counterpartyName;

    private String rmId;
    private String groupReference;
    private String borrowerType;
    private String segment;
    private String sector;
    private String country;

    /** Total proposed exposure across the borrower's live applications, by currency. */
    @Convert(converter = JsonAttributeConverters.MapConverter.class)
    @Column(length = 2000)
    private Map<String, Object> exposureByCurrency;

    private Double weightedAveragePd;
    private Double weightedAverageRaroc;
    private String latestGrade;
    private int facilityCount;
    private int applicationCount;

    /** The relationship's current product surface — for cross-sell signals. */
    @Convert(converter = JsonAttributeConverters.StringListConverter.class)
    @Column(length = 1000)
    private List<String> currentFacilityTypes;

    /** Suggested product surface to broaden — heuristic, advisory. */
    @Convert(converter = JsonAttributeConverters.StringListConverter.class)
    @Column(length = 1000)
    private List<String> potentialCrossSell;

    /** Three-scenario wallet projection (BEST / MOST_LIKELY / WORST). */
    @Convert(converter = JsonAttributeConverters.MapConverter.class)
    @Column(length = 4000)
    private Map<String, Object> walletSizing;

    /** Region / industry headwinds + tailwinds — grounded in macro overlay. */
    @Convert(converter = JsonAttributeConverters.MapConverter.class)
    @Column(length = 4000)
    private Map<String, Object> industryInsights;

    /** Peer / whitespace observations. */
    @Convert(converter = JsonAttributeConverters.StringListConverter.class)
    @Column(length = 2000)
    private List<String> peerInsights;

    /** Action nudges — gaps the RM should close before sign-off. */
    @Convert(converter = JsonAttributeConverters.StringListConverter.class)
    @Column(length = 2000)
    private List<String> completenessNudges;

    /** Citations: section → source endpoint (for grounding traceability). */
    @Convert(converter = JsonAttributeConverters.MapConverter.class)
    @Column(length = 4000)
    private Map<String, Object> citations;

    @Lob
    @Column(length = 16000)
    private String markdown;

    @Lob
    @Column(length = 32000)
    private String html;

    @Column(nullable = false, length = 20)
    private String status = "DRAFT";    // DRAFT | CONFIRMED | REJECTED

    private boolean advisory = true;

    private String generatedBy;          // AI capability marker
    private String reviewedBy;
    private Instant reviewedAt;
    private String reviewNote;

    @CreationTimestamp
    @Column(updatable = false)
    private Instant generatedAt;
}
