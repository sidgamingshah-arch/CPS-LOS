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
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;

/**
 * A Credit Administration (CAD) case — opened after CP approval to perfect
 * documentation against a checklist, handle waivers/deviations, and release limits
 * (PRD CAD module). One per approved/renewed/amended CP.
 */
@Entity
@Table(name = "cad_cases", indexes = {
        @Index(name = "idx_cad_app", columnList = "applicationRef"),
        @Index(name = "idx_cad_status", columnList = "status")
})
@Getter
@Setter
public class CadCase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 30)
    private String applicationRef;

    private String counterpartyName;

    @Column(nullable = false, length = 20)
    private String cpType;            // NEW | RENEWAL | AMENDMENT

    @Column(nullable = false, length = 30)
    private String status;            // CHECKLIST | IN_PROGRESS | DEVIATION | COMPLETED | LIMIT_RELEASED

    private String checklistKey;      // CHECKLIST_MASTER record used
    private String createdBy;

    @CreationTimestamp
    @Column(updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    private Instant updatedAt;
}
