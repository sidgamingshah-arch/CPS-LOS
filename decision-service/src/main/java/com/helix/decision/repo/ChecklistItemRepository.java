package com.helix.decision.repo;

import com.helix.decision.entity.ChecklistItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ChecklistItemRepository extends JpaRepository<ChecklistItem, Long> {
    List<ChecklistItem> findByCadCaseIdOrderByIdAsc(Long cadCaseId);
}
