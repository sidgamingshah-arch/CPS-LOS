package com.helix.common.query;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface QueryThreadRepository extends JpaRepository<QueryThread, Long> {

    Optional<QueryThread> findByQueryRef(String queryRef);

    List<QueryThread> findBySubjectRefOrderByIdDesc(String subjectRef);

    List<QueryThread> findByAddresseeOrderByIdDesc(String addressee);

    /** Threads the actor raised — one leg of the caller-scoped visibility set (Fix 2). */
    List<QueryThread> findByRaisedByOrderByIdDesc(String raisedBy);

    /** Threads directed at any role the caller holds — one leg of the caller-scoped set (Fix 2). */
    List<QueryThread> findByAddresseeRoleInOrderByIdDesc(Collection<String> addresseeRoles);

    List<QueryThread> findTop200ByOrderByIdDesc();

    /** SCHEDULED threads whose deferred release time has arrived (platform sweep). */
    List<QueryThread> findByStatusAndScheduleAtLessThanEqualOrderByIdAsc(QueryStatus status, Instant cutoff);

    /**
     * Resolve the single thread whose stored one-time response-token hash matches (self-service
     * portal). Only the SHA-256 hash is ever persisted, so the caller hashes the presented raw
     * token and looks it up here — the token IS the lookup key, which structurally scopes access
     * to EXACTLY ONE thread (no thread id in the path, so no IDOR). Mirrors the
     * {@code findByApproveTokenHash} lookup on the notification action lane.
     */
    Optional<QueryThread> findByResponseTokenHash(String responseTokenHash);
}
