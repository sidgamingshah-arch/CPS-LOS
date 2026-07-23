package com.helix.origination.repo;

import com.helix.origination.entity.InformationMemorandum;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface InformationMemorandumRepository extends JpaRepository<InformationMemorandum, Long> {

    /** Every IM/version for a deal, newest version first. */
    List<InformationMemorandum> findByApplicationReferenceOrderByVersionDescIdDesc(String applicationReference);

    Optional<InformationMemorandum> findByImRef(String imRef);
}
