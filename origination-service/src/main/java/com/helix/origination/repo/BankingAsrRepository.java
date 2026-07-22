package com.helix.origination.repo;

import com.helix.origination.entity.BankingAsr;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface BankingAsrRepository extends JpaRepository<BankingAsr, Long> {

    Optional<BankingAsr> findByAsrRef(String asrRef);

    boolean existsByAsrRef(String asrRef);

    List<BankingAsr> findAllByOrderByCreatedAtDesc();

    List<BankingAsr> findByApplicationRefOrderByCreatedAtDesc(String applicationRef);
}
