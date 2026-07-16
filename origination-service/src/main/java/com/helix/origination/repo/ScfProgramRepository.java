package com.helix.origination.repo;

import com.helix.origination.entity.ScfProgram;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ScfProgramRepository extends JpaRepository<ScfProgram, Long> {

    Optional<ScfProgram> findByScfRef(String scfRef);

    boolean existsByScfRef(String scfRef);

    List<ScfProgram> findAllByOrderByCreatedAtDesc();

    List<ScfProgram> findByAnchorRefOrderByCreatedAtDesc(String anchorRef);
}
