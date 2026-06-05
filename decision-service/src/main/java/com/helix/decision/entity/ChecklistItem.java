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
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;

/** A documentation checklist item within a CAD case. */
@Entity
@Table(name = "cad_checklist_items", indexes = {
        @Index(name = "idx_cli_case", columnList = "cadCaseId")
})
@Getter
@Setter
public class ChecklistItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long cadCaseId;

    @Column(nullable = false)
    private String code;

    @Column(nullable = false)
    private String description;

    @Column(nullable = false)
    private boolean mandatory;

    @Column(nullable = false, length = 20)
    private String status;            // PENDING | SUBMITTED | COMPLIED | NON_COMPLIED | WAIVED | DEVIATION

    private String docRef;            // DMS reference
    private String comment;
    private String updatedBy;

    @UpdateTimestamp
    private Instant updatedAt;
}
