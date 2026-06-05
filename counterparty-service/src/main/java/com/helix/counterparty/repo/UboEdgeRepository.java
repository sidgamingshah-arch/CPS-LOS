package com.helix.counterparty.repo;

import com.helix.counterparty.entity.UboEdge;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface UboEdgeRepository extends JpaRepository<UboEdge, Long> {
    List<UboEdge> findByCounterpartyId(Long counterpartyId);

    void deleteByCounterpartyId(Long counterpartyId);
}
