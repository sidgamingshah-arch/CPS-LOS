package com.helix.decision.dto;

import jakarta.validation.constraints.NotBlank;

/** Conflict-of-interest attestation payloads. DTOs are records (project convention). */
public class CoiDtos {

    /**
     * Attest against a subject. The attester's identity is the X-Actor header — never
     * this body — so an actor can only ever record <b>their own</b> attestation.
     */
    public record AttestRequest(@NotBlank String subjectType, @NotBlank String subjectRef,
                                String role, @NotBlank String declaration, String note) {
    }
}
