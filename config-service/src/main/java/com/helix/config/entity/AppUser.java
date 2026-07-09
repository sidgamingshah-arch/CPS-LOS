package com.helix.config.entity;

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
 * A platform user — the credential behind a named actor. {@code actorKey} maps to the
 * {@code ACTOR_ROLE} master recordKey (usually identical to the username), so login
 * verifies WHO the caller is and the existing RBAC directory decides WHAT they may do.
 * Passwords are PBKDF2-hashed (never stored or logged in clear).
 */
@Entity
@Table(name = "app_users", indexes = {
        @Index(name = "idx_appuser_username", columnList = "username", unique = true)
})
@Getter
@Setter
public class AppUser {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 80)
    private String username;

    @Column(nullable = false, length = 120)
    private String displayName;

    /** The ACTOR_ROLE key this login acts as (the value injected as X-Actor). */
    @Column(nullable = false, length = 80)
    private String actorKey;

    /** PBKDF2 stored form: iterations:salt:hash. */
    @Column(nullable = false, length = 200)
    private String passwordHash;

    @Column(nullable = false)
    private boolean active = true;

    @CreationTimestamp
    @Column(updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    private Instant updatedAt;
}
