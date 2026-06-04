package com.helix.counterparty.repo;

import com.helix.counterparty.entity.CounterpartyGroup;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CounterpartyGroupRepository extends JpaRepository<CounterpartyGroup, Long> {
}
