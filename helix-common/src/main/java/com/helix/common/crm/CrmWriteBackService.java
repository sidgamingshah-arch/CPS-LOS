package com.helix.common.crm;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.helix.common.audit.AuditService;
import com.helix.common.export.DownstreamSystem;
import com.helix.common.export.Export;
import com.helix.common.web.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * CRM write-back service — assembles a canonical {@link Export.Envelope} of one
 * {@link Export.CrmCaseStatusRecord}, relays it through the active {@link CrmConnector}
 * ({@code simulated} default / {@code live}), persists an idempotent {@link CrmWriteBack} row
 * (keyed by case/subject + as-of day), and stamps a {@code CRM_WRITEBACK_*} SYSTEM audit event —
 * exactly the shape of the downstream export façade. Re-triggering the same write-back for the
 * same as-of day returns the existing row (idempotent; no duplicate push).
 */
@Service
public class CrmWriteBackService {

    private static final String VERSION = "1.0";

    private final CrmWriteBackRepository repo;
    private final CrmConnector connector;
    private final AuditService audit;
    private final ObjectMapper mapper;

    public CrmWriteBackService(CrmWriteBackRepository repo, CrmConnector connector, AuditService audit,
                               ObjectMapper mapper) {
        this.repo = repo;
        this.connector = connector;
        this.audit = audit;
        this.mapper = mapper;
    }

    /** A case/decision status to push back to the CRM. {@code asOf} defaults to today. */
    public record Request(String subjectType, String subjectRef, String caseRef, String stage,
                          String status, String decision, String decisionBy, String decisionAt,
                          String comments, String asOf) {
    }

    @Transactional
    public CrmWriteBack writeBack(Request req, String actor) {
        if (req == null || ((req.caseRef() == null || req.caseRef().isBlank())
                && (req.subjectRef() == null || req.subjectRef().isBlank()))) {
            throw ApiException.badRequest("caseRef or subjectRef is required");
        }
        String asOf = req.asOf() != null && !req.asOf().isBlank() ? req.asOf() : LocalDate.now().toString();
        String subjectKey = req.caseRef() != null && !req.caseRef().isBlank() ? req.caseRef() : req.subjectRef();
        String key = "CRM:CASE_STATUS:" + subjectKey + ":" + asOf;

        CrmWriteBack existing = repo.findByIdempotencyKey(key).orElse(null);
        if (existing != null) return existing;   // idempotent — never re-push the same day

        Export.CrmCaseStatusRecord rec = new Export.CrmCaseStatusRecord(
                req.subjectType(), req.subjectRef(), req.caseRef(), req.stage(), req.status(),
                req.decision(), req.decisionBy(), req.decisionAt(), req.comments());
        Export.Envelope<Export.CrmCaseStatusRecord> env = Export.Envelope.of(
                DownstreamSystem.CRM, "CASE_STATUS", key, VERSION, List.of(rec));

        CrmConnector.Result result = connector.push(env);

        CrmWriteBack row = new CrmWriteBack();
        row.setDestination(DownstreamSystem.CRM.name());
        row.setFeedType(env.feedType());
        row.setIdempotencyKey(key);
        row.setAsOf(asOf);
        row.setSubjectType(req.subjectType());
        row.setSubjectRef(req.subjectRef());
        row.setCaseRef(req.caseRef());
        row.setStage(req.stage());
        row.setCaseStatus(req.status());
        row.setMode(connector.mode());
        row.setDeliveryStatus(result.deliveryStatus());
        row.setProviderRef(result.providerRef());
        row.setFailureReason(result.failureReason());
        row.setRecordCount(env.recordCount());
        row.setGeneratedBy(actor);
        row.setEnvelope(mapper.convertValue(env, new TypeReference<Map<String, Object>>() {
        }));
        CrmWriteBack saved = repo.save(row);

        audit.engine("CRM_WRITEBACK_" + result.deliveryStatus(), "CrmWriteBack", String.valueOf(saved.getId()),
                "CRM case-status write-back %s (%s) for %s".formatted(result.deliveryStatus(),
                        connector.mode(), subjectKey),
                Map.of("mode", connector.mode(), "deliveryStatus", result.deliveryStatus(),
                        "idempotencyKey", key, "subjectKey", subjectKey,
                        "caseStatus", String.valueOf(req.status()), "triggeredBy", String.valueOf(actor),
                        "records", env.recordCount()));
        return saved;
    }

    @Transactional(readOnly = true)
    public List<CrmWriteBack> list(String subjectRef) {
        return subjectRef == null || subjectRef.isBlank()
                ? repo.findAllByOrderByIdDesc()
                : repo.findBySubjectRefOrderByIdDesc(subjectRef);
    }

    @Transactional(readOnly = true)
    public CrmWriteBack get(Long id) {
        return repo.findById(id).orElseThrow(() -> ApiException.notFound("No CRM write-back: " + id));
    }
}
