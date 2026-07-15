package com.helix.portfolio.repo;

import com.helix.portfolio.entity.EscrowAccount;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface EscrowAccountRepository extends JpaRepository<EscrowAccount, Long> {

    Optional<EscrowAccount> findByEscrowRef(String escrowRef);

    List<EscrowAccount> findAllByOrderByIdDesc();

    List<EscrowAccount> findBySubjectRefOrderByIdDesc(String subjectRef);
}
