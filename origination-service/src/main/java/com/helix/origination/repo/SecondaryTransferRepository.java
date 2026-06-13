package com.helix.origination.repo;

import com.helix.origination.entity.SecondaryTransfer;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SecondaryTransferRepository extends JpaRepository<SecondaryTransfer, Long> {
    List<SecondaryTransfer> findByApplicationReferenceOrderByIdDesc(String applicationReference);
}
