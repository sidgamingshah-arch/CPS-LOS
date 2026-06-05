package com.helix.decision.repo;

import com.helix.decision.entity.CovenantSchedule;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface CovenantScheduleRepository extends JpaRepository<CovenantSchedule, Long> {
    List<CovenantSchedule> findByApplicationReferenceOrderByCurrentDueDateAsc(String applicationReference);

    Optional<CovenantSchedule> findFirstByCovenantIdOrderByIdDesc(Long covenantId);

    List<CovenantSchedule> findByCurrentDueDateBeforeAndStatusNot(LocalDate date, String status);

    List<CovenantSchedule> findByCurrentDueDateBetweenAndStatusNot(LocalDate from, LocalDate to, String status);
}
