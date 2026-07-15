package com.helix.origination.repo;

import com.helix.origination.entity.ScfSpoke;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ScfSpokeRepository extends JpaRepository<ScfSpoke, Long> {

    List<ScfSpoke> findByProgramRefOrderByIdAsc(String programRef);
}
