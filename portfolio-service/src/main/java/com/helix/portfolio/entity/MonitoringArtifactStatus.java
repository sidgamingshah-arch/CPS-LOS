package com.helix.portfolio.entity;

/**
 * One lifecycle shared by every monitoring-artifact type (call memo, plant visit,
 * LCR, QPR, broker review, stock audit, audit note). AUTHORIZED is only reachable
 * for artifact types whose master carries {@code requiresAuthorize=true}.
 */
public enum MonitoringArtifactStatus {
    DRAFT, SUBMITTED, REVIEWED, APPROVED, AUTHORIZED
}
