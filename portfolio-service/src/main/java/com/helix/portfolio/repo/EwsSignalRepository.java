package com.helix.portfolio.repo;

import com.helix.portfolio.entity.EwsSignal;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface EwsSignalRepository extends JpaRepository<EwsSignal, Long> {
    List<EwsSignal> findByApplicationReferenceOrderByScoreDesc(String applicationReference);

    List<EwsSignal> findByStatusOrderByScoreDesc(String status);
}
