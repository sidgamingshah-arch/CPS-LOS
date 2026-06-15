package com.helix.config.service;

import com.helix.common.audit.AuditService;
import com.helix.common.web.ApiException;
import com.helix.config.entity.MasterRecord;
import com.helix.config.repo.MasterRecordRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Generic master-data engine with maker-checker governance. Backs every "X Master"
 * in the platform; the entity-specific masters are just different {@code masterType}
 * values over the same lifecycle: create (maker) → approve/reject (checker) →
 * ACTIVE, versioned. Bulk upsert is supported for spreadsheet-style maintenance.
 */
@Service
public class MasterDataService {

    private static final String PENDING = "PENDING_APPROVAL";
    private static final String ACTIVE = "ACTIVE";
    private static final String REJECTED = "REJECTED";
    private static final String INACTIVE = "INACTIVE";

    private final MasterRecordRepository repo;
    private final AuditService audit;

    public MasterDataService(MasterRecordRepository repo, AuditService audit) {
        this.repo = repo;
        this.audit = audit;
    }

    /** Maker submits a new/updated record; it enters the approval queue. */
    @Transactional
    public MasterRecord submit(String masterType, String recordKey, String jurisdiction,
                               Map<String, Object> payload, String maker) {
        // Version within the (type, key, jurisdiction) scope — a jurisdiction override
        // has its own version line, independent of the default record for the key.
        int version = versionsFor(masterType.toUpperCase(), recordKey, jurisdiction)
                .stream().mapToInt(MasterRecord::getVersion).max().orElse(0) + 1;
        MasterRecord m = new MasterRecord();
        m.setMasterType(masterType.toUpperCase());
        m.setRecordKey(recordKey);
        m.setJurisdiction(jurisdiction);
        m.setPayload(payload == null ? Map.of() : payload);
        m.setStatus(PENDING);
        m.setVersion(version);
        m.setMaker(maker);
        m.setMakerAt(Instant.now());
        MasterRecord saved = repo.save(m);
        audit.human(maker, "MASTER_SUBMITTED", "Master:" + masterType, recordKey,
                "Submitted %s/%s v%d for approval".formatted(masterType, recordKey, version),
                Map.of("masterType", masterType, "recordKey", recordKey, "version", version));
        return saved;
    }

    /** Bulk submit (spreadsheet-style). Each item: {recordKey, payload, jurisdiction?}. */
    @Transactional
    public List<MasterRecord> bulkSubmit(String masterType, List<Map<String, Object>> rows, String maker) {
        return rows.stream().map(row -> {
            String key = String.valueOf(row.get("recordKey"));
            String jur = row.get("jurisdiction") == null ? null : String.valueOf(row.get("jurisdiction"));
            @SuppressWarnings("unchecked")
            Map<String, Object> payload = row.get("payload") instanceof Map<?, ?> p
                    ? (Map<String, Object>) p : Map.of();
            return submit(masterType, key, jur, payload, maker);
        }).toList();
    }

    /** Checker approves: supersedes the prior ACTIVE record for the same key. SoD enforced. */
    @Transactional
    public MasterRecord approve(Long id, String checker, String comment) {
        MasterRecord m = get(id);
        if (!PENDING.equals(m.getStatus())) {
            throw ApiException.conflict("Record is not pending approval");
        }
        if (checker != null && checker.equalsIgnoreCase(m.getMaker())) {
            throw ApiException.forbiddenAutonomy("Maker and checker must differ (segregation of duties)");
        }
        // Supersede ONLY the prior ACTIVE record of the same (type, key, jurisdiction) scope.
        // A jurisdiction override and the null-jurisdiction default are independent lines:
        // approving an override must not deactivate the default (and vice-versa).
        activeFor(m.getMasterType(), m.getRecordKey(), m.getJurisdiction())
                .ifPresent(prev -> {
                    prev.setStatus(INACTIVE);
                    repo.save(prev);
                });
        m.setStatus(ACTIVE);
        m.setChecker(checker);
        m.setCheckerAt(Instant.now());
        m.setComment(comment);
        audit.human(checker, "MASTER_APPROVED", "Master:" + m.getMasterType(), m.getRecordKey(),
                "Approved %s/%s v%d".formatted(m.getMasterType(), m.getRecordKey(), m.getVersion()),
                Map.of("masterType", m.getMasterType(), "recordKey", m.getRecordKey()));
        return repo.save(m);
    }

    @Transactional
    public MasterRecord reject(Long id, String checker, String comment) {
        MasterRecord m = get(id);
        if (!PENDING.equals(m.getStatus())) {
            throw ApiException.conflict("Record is not pending approval");
        }
        if (checker != null && checker.equalsIgnoreCase(m.getMaker())) {
            throw ApiException.forbiddenAutonomy("Maker and checker must differ (segregation of duties)");
        }
        m.setStatus(REJECTED);
        m.setChecker(checker);
        m.setCheckerAt(Instant.now());
        m.setComment(comment);
        audit.human(checker, "MASTER_REJECTED", "Master:" + m.getMasterType(), m.getRecordKey(),
                "Rejected %s/%s v%d".formatted(m.getMasterType(), m.getRecordKey(), m.getVersion()), Map.of());
        return repo.save(m);
    }

    /** Seeds an already-approved record (used by the platform seeder). */
    @Transactional
    public MasterRecord seedActive(String masterType, String recordKey, String jurisdiction, Map<String, Object> payload) {
        MasterRecord m = new MasterRecord();
        m.setMasterType(masterType.toUpperCase());
        m.setRecordKey(recordKey);
        m.setJurisdiction(jurisdiction);
        m.setPayload(payload);
        m.setStatus(ACTIVE);
        m.setVersion(1);
        m.setMaker("seed.maker");
        m.setMakerAt(Instant.now());
        m.setChecker("seed.checker");
        m.setCheckerAt(Instant.now());
        return repo.save(m);
    }

    @Transactional(readOnly = true)
    public List<MasterRecord> listActive(String masterType) {
        return repo.findByMasterTypeAndStatusOrderByRecordKeyAsc(masterType.toUpperCase(), ACTIVE);
    }

    /**
     * Resolve the active record for a key, jurisdiction-agnostic. Prefers the
     * null-jurisdiction default; if absent, falls back to any active record for the
     * key. Used by callers that don't carry a jurisdiction (e.g. ACTOR_ROLE).
     */
    @Transactional(readOnly = true)
    public MasterRecord active(String masterType, String recordKey) {
        String type = masterType.toUpperCase();
        return repo.findFirstByMasterTypeAndRecordKeyAndJurisdictionIsNullAndStatusOrderByVersionDesc(type, recordKey, ACTIVE)
                .or(() -> repo.findFirstByMasterTypeAndRecordKeyAndStatusOrderByVersionDesc(type, recordKey, ACTIVE))
                .orElseThrow(() -> ApiException.notFound("No active %s/%s".formatted(masterType, recordKey)));
    }

    /**
     * Jurisdiction-aware resolution: a jurisdiction-specific override wins, otherwise
     * the null-jurisdiction default applies. This is the resolution contract every
     * regime-overlay consumer relies on — overrides and defaults coexist, the override
     * shadows the default only for its own jurisdiction.
     */
    @Transactional(readOnly = true)
    public MasterRecord resolve(String masterType, String recordKey, String jurisdiction) {
        String type = masterType.toUpperCase();
        if (jurisdiction != null && !jurisdiction.isBlank()) {
            var override = repo.findFirstByMasterTypeAndRecordKeyAndJurisdictionAndStatusOrderByVersionDesc(
                    type, recordKey, jurisdiction, ACTIVE);
            if (override.isPresent()) {
                return override.get();
            }
        }
        return repo.findFirstByMasterTypeAndRecordKeyAndJurisdictionIsNullAndStatusOrderByVersionDesc(type, recordKey, ACTIVE)
                .or(() -> repo.findFirstByMasterTypeAndRecordKeyAndStatusOrderByVersionDesc(type, recordKey, ACTIVE))
                .orElseThrow(() -> ApiException.notFound(
                        "No active %s/%s for jurisdiction %s".formatted(masterType, recordKey, jurisdiction)));
    }

    @Transactional(readOnly = true)
    public List<MasterRecord> pendingQueue() {
        return repo.findByStatusOrderByMakerAtAsc(PENDING);
    }

    @Transactional(readOnly = true)
    public List<MasterRecord> history(String masterType, String recordKey) {
        return repo.findByMasterTypeAndRecordKeyOrderByVersionDesc(masterType.toUpperCase(), recordKey);
    }

    @Transactional(readOnly = true)
    public MasterRecord get(Long id) {
        return repo.findById(id).orElseThrow(() -> ApiException.notFound("No master record: " + id));
    }

    @Transactional(readOnly = true)
    public boolean hasAny() {
        return repo.count() > 0;
    }

    // ---- jurisdiction-scoped helpers: a (type, key, jurisdiction) triple is the unit
    // of versioning and supersession; a null jurisdiction = the default line. ----

    /** All versions for the (type, key, jurisdiction) scope — the version line that submit increments. */
    private List<MasterRecord> versionsFor(String masterType, String recordKey, String jurisdiction) {
        return (jurisdiction == null || jurisdiction.isBlank())
                ? repo.findByMasterTypeAndRecordKeyAndJurisdictionIsNull(masterType, recordKey)
                : repo.findByMasterTypeAndRecordKeyAndJurisdiction(masterType, recordKey, jurisdiction);
    }

    /** The current ACTIVE record for the exact (type, key, jurisdiction) scope, if any. */
    private java.util.Optional<MasterRecord> activeFor(String masterType, String recordKey, String jurisdiction) {
        return (jurisdiction == null || jurisdiction.isBlank())
                ? repo.findFirstByMasterTypeAndRecordKeyAndJurisdictionIsNullAndStatusOrderByVersionDesc(masterType, recordKey, ACTIVE)
                : repo.findFirstByMasterTypeAndRecordKeyAndJurisdictionAndStatusOrderByVersionDesc(masterType, recordKey, jurisdiction, ACTIVE);
    }
}
