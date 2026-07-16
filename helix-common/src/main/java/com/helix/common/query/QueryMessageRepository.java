package com.helix.common.query;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface QueryMessageRepository extends JpaRepository<QueryMessage, Long> {

    List<QueryMessage> findByQueryRefOrderByIdAsc(String queryRef);
}
