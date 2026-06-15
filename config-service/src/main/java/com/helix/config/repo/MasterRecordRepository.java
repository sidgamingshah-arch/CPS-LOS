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

    // ---- jurisdiction-scoped (a record is unique on masterType+recordKey+jurisdiction;
    // null jurisdiction = the default record). JPA can't bind "= null", hence the
    // explicit IsNull variants. ----

    Optional<MasterRecord> findFirstByMasterTypeAndRecordKeyAndJurisdictionAndStatusOrderByVersionDesc(
            String masterType, String recordKey, String jurisdiction, String status);

    Optional<MasterRecord> findFirstByMasterTypeAndRecordKeyAndJurisdictionIsNullAndStatusOrderByVersionDesc(
            String masterType, String recordKey, String status);

    List<MasterRecord> findByMasterTypeAndRecordKeyAndJurisdiction(
            String masterType, String recordKey, String jurisdiction);

    List<MasterRecord> findByMasterTypeAndRecordKeyAndJurisdictionIsNull(
            String masterType, String recordKey);

    List<MasterRecord> findByStatusOrderByMakerAtAsc(String status);

    List<MasterRecord> findByMasterTypeOrderByRecordKeyAsc(String masterType);
}
