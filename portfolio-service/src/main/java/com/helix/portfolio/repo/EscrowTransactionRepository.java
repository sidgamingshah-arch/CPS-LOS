package com.helix.portfolio.repo;

import com.helix.portfolio.entity.EscrowTransaction;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface EscrowTransactionRepository extends JpaRepository<EscrowTransaction, Long> {

    List<EscrowTransaction> findByAccountRefOrderByIdDesc(String accountRef);
}
