package com.helix.decision.repo;

import com.helix.decision.entity.MerItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;

public interface MerItemRepository extends JpaRepository<MerItem, Long> {

    List<MerItem> findByApplicationReferenceOrderByDueDateAsc(String applicationReference);

    List<MerItem> findByOwnerOrderByDueDateAsc(String owner);

    List<MerItem> findByStatusOrderByDueDateAsc(String status);

    List<MerItem> findByOwnerAndStatusOrderByDueDateAsc(String owner, String status);

    List<MerItem> findByStatusInOrderByDueDateAsc(List<String> statuses);

    List<MerItem> findByDueDateBetweenAndStatusInOrderByDueDateAsc(LocalDate from, LocalDate to, List<String> statuses);

    boolean existsByApplicationReferenceAndSourceRef(String applicationReference, String sourceRef);
}
