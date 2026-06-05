package com.helix.counterparty.repo;

import com.helix.counterparty.entity.UboNode;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface UboNodeRepository extends JpaRepository<UboNode, Long> {
    List<UboNode> findByCounterpartyId(Long counterpartyId);

    void deleteByCounterpartyId(Long counterpartyId);
}
