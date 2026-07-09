package com.helix.counterparty.repo;

import com.helix.counterparty.entity.CounterpartyGroup;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface CounterpartyGroupRepository extends JpaRepository<CounterpartyGroup, Long> {
    Optional<CounterpartyGroup> findByReference(String reference);
}
