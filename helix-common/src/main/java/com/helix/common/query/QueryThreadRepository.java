package com.helix.common.query;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface QueryThreadRepository extends JpaRepository<QueryThread, Long> {

    Optional<QueryThread> findByQueryRef(String queryRef);

    List<QueryThread> findBySubjectRefOrderByIdDesc(String subjectRef);

    List<QueryThread> findByAddresseeOrderByIdDesc(String addressee);

    List<QueryThread> findTop200ByOrderByIdDesc();

    /** SCHEDULED threads whose deferred release time has arrived (platform sweep). */
    List<QueryThread> findByStatusAndScheduleAtLessThanEqualOrderByIdAsc(QueryStatus status, Instant cutoff);
}
