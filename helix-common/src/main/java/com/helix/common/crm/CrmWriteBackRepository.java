package com.helix.common.crm;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CrmWriteBackRepository extends JpaRepository<CrmWriteBack, Long> {

    Optional<CrmWriteBack> findByIdempotencyKey(String idempotencyKey);

    List<CrmWriteBack> findBySubjectRefOrderByIdDesc(String subjectRef);

    List<CrmWriteBack> findAllByOrderByIdDesc();
}
