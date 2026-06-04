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

/**
 * A waiver / deviation on a checklist item, requiring a sequential two-level
 * approval (PRD CAD waiver/deviation workflow). Segregation of duties is enforced:
 * neither approver may be the raiser, and L2 must differ from L1.
 */
@Entity
@Table(name = "cad_deviations", indexes = {
        @Index(name = "idx_dev_case", columnList = "cadCaseId")
})
@Getter
@Setter
public class Deviation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long cadCaseId;

    @Column(nullable = false)
    private Long checklistItemId;

    @Column(nullable = false, length = 20)
    private String type;              // WAIVER | DEVIATION

    @Column(nullable = false)
    private String reason;

    @Column(nullable = false, length = 20)
    private String status;            // PENDING_L1 | PENDING_L2 | APPROVED | REJECTED

    private String raisedBy;
    private String approverL1;
    private String approverL2;
    private String decisionComment;

    @CreationTimestamp
    @Column(updatable = false)
    private Instant createdAt;
}
