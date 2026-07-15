package com.helix.workflow.repo;

import com.helix.workflow.entity.QueueCursor;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface QueueCursorRepository extends JpaRepository<QueueCursor, Long> {

    Optional<QueueCursor> findByQueueKey(String queueKey);
}
