package com.helix.workflow.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * Round-robin position per queue. {@code index} is a SQLite reserved word, so the
 * column is named {@code last_index} (matches the platform's {@code stage_key}
 * fix). One row per {@code queueKey}.
 */
@Entity
@Table(name = "queue_cursors")
@Getter
@Setter
public class QueueCursor {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "queue_key", nullable = false, unique = true, length = 60)
    private String queueKey;

    /** Index of the member assigned last; the next assignment starts at +1. Starts at -1. */
    @Column(name = "last_index", nullable = false)
    private int lastIndex = -1;
}
