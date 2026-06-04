package com.helix.counterparty.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;

/** A borrower group for exposure aggregation and group-RM ownership (PRD §7). */
@Entity
@Table(name = "counterparty_groups")
@Getter
@Setter
public class CounterpartyGroup {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 30)
    private String reference;

    @Column(nullable = false)
    private String name;

    private String groupRmId;

    private String country;

    /** True when the group spans more than one country of risk/incorporation. */
    private boolean multiCountry;

    @CreationTimestamp
    @Column(updatable = false)
    private Instant createdAt;
}
