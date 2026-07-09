package com.helix.risk.repo;

import com.helix.risk.entity.ModelAnswer;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ModelAnswerRepository extends JpaRepository<ModelAnswer, Long> {
    List<ModelAnswer> findByInstanceIdOrderByIdAsc(Long instanceId);
}
