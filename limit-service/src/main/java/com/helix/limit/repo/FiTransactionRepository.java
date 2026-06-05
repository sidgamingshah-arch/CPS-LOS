package com.helix.limit.repo;

import com.helix.limit.entity.FiTransaction;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface FiTransactionRepository extends JpaRepository<FiTransaction, Long> {
    Optional<FiTransaction> findByFid(String fid);

    List<FiTransaction> findByStatusOrderBySubmittedAtAsc(String status);
}
