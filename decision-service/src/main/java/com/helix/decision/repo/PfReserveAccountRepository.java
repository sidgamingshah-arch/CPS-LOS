package com.helix.decision.repo;

import com.helix.decision.entity.PfReserveAccount;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PfReserveAccountRepository extends JpaRepository<PfReserveAccount, Long> {
    List<PfReserveAccount> findByApplicationReferenceOrderByIdAsc(String applicationReference);
}
