package com.helix.decision.entity;

/**
 * Lifecycle of a CAM annexure. DRAFT → SUBMITTED → REVIEWED → APPROVED is the happy
 * path; a submitted / reviewed annexure may instead be REJECTED (with a mandatory
 * reason). All transitions are human-gated and stamp an audit event.
 */
public enum AnnexureStatus {
    DRAFT, SUBMITTED, REVIEWED, APPROVED, REJECTED
}
