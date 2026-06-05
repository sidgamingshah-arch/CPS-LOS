package com.helix.limit.entity;

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

/**
 * Department-level (e.g. FI / Corporate / BBG) exposure under a country, with
 * gross OSUC, indirect risk, sell-down, settlement and cash collateral.
 * Departments are non-fungible — utilisation moves to the right department only.
 */
@Entity
@Table(name = "department_limits", indexes = {
        @Index(name = "idx_dept", columnList = "country,department", unique = true)
})
@Getter
@Setter
public class DepartmentLimit {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 5)
    private String country;

    @Column(nullable = false, length = 30)
    private String department;       // FI | CORPORATE | BBG | ...

    @Column(name = "dept_limit", nullable = false)
    private double limit;
    @Column(nullable = false, length = 5)
    private String currency;

    private double grossOsuc;
    private double indirectRisk;
    private double sellDown;
    private double settlementLimit;
    private double cashCollateral;

    private double tenorLt1y;
    private double tenor1to3y;
    private double tenor3to5y;
    private double tenorGt5y;

    @Column(nullable = false, length = 20)
    private String status = "ACTIVE";

    @UpdateTimestamp
    private Instant updatedAt;

    /** Net exposure = Gross OSUC − Cash Collateral. */
    public double netExposure() {
        return Math.max(0.0, grossOsuc - cashCollateral);
    }
}
