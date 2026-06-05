package com.helix.config.repo;

import com.helix.config.entity.MasterRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface MasterRecordRepository extends JpaRepository<MasterRecord, Long> {

    List<MasterRecord> findByMasterTypeAndStatusOrderByRecordKeyAsc(String masterType, String status);

    Optional<MasterRecord> findFirstByMasterTypeAndRecordKeyAndStatusOrderByVersionDesc(
            String masterType, String recordKey, String status);

    List<MasterRecord> findByMasterTypeAndRecordKeyOrderByVersionDesc(String masterType, String recordKey);

    List<MasterRecord> findByStatusOrderByMakerAtAsc(String status);

    List<MasterRecord> findByMasterTypeOrderByRecordKeyAsc(String masterType);
}
