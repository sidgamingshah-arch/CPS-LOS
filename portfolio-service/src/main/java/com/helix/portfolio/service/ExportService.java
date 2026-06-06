package com.helix.portfolio.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.helix.common.audit.AuditService;
import com.helix.common.export.DownstreamSystem;
import com.helix.common.export.Export;
import com.helix.common.export.Export.CprPortfolioLine;
import com.helix.common.export.Export.ErmRiskRecord;
import com.helix.common.export.Export.FinanceProvisionEntry;
import com.helix.common.web.ApiException;
import com.helix.portfolio.entity.EclResult;
import com.helix.portfolio.entity.ExportBatch;
import com.helix.portfolio.entity.ExposureRecord;
import com.helix.portfolio.repo.EclResultRepository;
import com.helix.portfolio.repo.ExportBatchRepository;
import com.helix.portfolio.repo.ExposureRecordRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Outbound canonical export feeds (PRD downstream ERM / Finance-GL / CPR interfaces).
 * The outbound counterpart to the connector ingestion: each feed assembles typed
 * canonical records ({@link com.helix.common.export.Export}) into an idempotent
 * {@code Export.Envelope}, persists it as an {@link ExportBatch}, and audits it.
 * Re-running a feed for the same as-of day returns the existing batch (idempotent).
 */
@Service
public class ExportService {

    private static final String VERSION = "1.0";

    private final ExposureRecordRepository exposures;
    private final EclResultRepository ecls;
    private final MisService mis;
    private final ExportBatchRepository batches;
    private final AuditService audit;
    private final ObjectMapper mapper;

    public ExportService(ExposureRecordRepository exposures, EclResultRepository ecls, MisService mis,
                         ExportBatchRepository batches, AuditService audit, ObjectMapper mapper) {
        this.exposures = exposures;
        this.ecls = ecls;
        this.mis = mis;
        this.batches = batches;
        this.audit = audit;
        this.mapper = mapper;
    }

    // --------------------------------------------------- ERM

    @Transactional
    public ExportBatch generateErm(String actor) {
        String asOf = LocalDate.now().toString();
        String key = "ERM:OBLIGOR_RISK:" + asOf;
        ExportBatch existing = batches.findByIdempotencyKey(key).orElse(null);
        if (existing != null) return existing;

        List<ErmRiskRecord> recs = new ArrayList<>();
        for (ExposureRecord e : exposures.findAll()) {
            EclResult ecl = ecls.findFirstByApplicationReferenceOrderByCreatedAtDesc(e.getApplicationReference())
                    .orElse(null);
            recs.add(new ErmRiskRecord(
                    e.getCounterpartyRef(), e.getCounterpartyName(), e.getSegment(), e.getJurisdiction(),
                    e.getSector(), e.getFinalGrade(), e.getPd(), e.getLgd(), e.getEad(), e.getRwa(),
                    e.getCapitalRequired(), ecl == null ? "UNSTAGED" : ecl.getStage(),
                    ecl == null ? 0.0 : ecl.getEcl(), e.getDaysPastDue(), e.getCurrency()));
        }
        return persist(Export.Envelope.of(DownstreamSystem.ERM, "OBLIGOR_RISK", key, VERSION, recs), asOf, actor);
    }

    // --------------------------------------------------- Finance / GL

    @Transactional
    public ExportBatch generateFinanceGl(String actor) {
        String asOf = LocalDate.now().toString();
        String key = "FINANCE_GL:PROVISION_ENTRY:" + asOf;
        ExportBatch existing = batches.findByIdempotencyKey(key).orElse(null);
        if (existing != null) return existing;

        List<FinanceProvisionEntry> recs = new ArrayList<>();
        for (ExposureRecord e : exposures.findAll()) {
            EclResult ecl = ecls.findFirstByApplicationReferenceOrderByCreatedAtDesc(e.getApplicationReference())
                    .orElse(null);
            if (ecl == null) continue;
            recs.add(new FinanceProvisionEntry(
                    e.getCounterpartyRef(), glAccount(ecl.getStage()), ecl.getStage(),
                    round2(ecl.getReportedProvision()), ecl.getReportedProvisionPolicy(),
                    e.getCurrency(), asOf));
        }
        return persist(Export.Envelope.of(DownstreamSystem.FINANCE_GL, "PROVISION_ENTRY", key, VERSION, recs), asOf, actor);
    }

    // --------------------------------------------------- CPR

    @Transactional
    @SuppressWarnings("unchecked")
    public ExportBatch generateCpr(String actor) {
        String asOf = LocalDate.now().toString();
        String key = "CPR:PORTFOLIO_COMPOSITION:" + asOf;
        ExportBatch existing = batches.findByIdempotencyKey(key).orElse(null);
        if (existing != null) return existing;

        Map<String, Object> comp = mis.bookComposition();
        double totalEad = coerce(comp.get("totalEad"));
        List<CprPortfolioLine> recs = new ArrayList<>();
        for (String[] dim : new String[][]{{"segment", "bySegment"}, {"grade", "byGrade"},
                {"jurisdiction", "byJurisdiction"}, {"status", "byStatus"}}) {
            Object node = comp.get(dim[1]);
            if (!(node instanceof Map<?, ?> buckets)) continue;
            for (Map.Entry<?, ?> en : buckets.entrySet()) {
                double ead = coerce(en.getValue());
                double share = totalEad > 0 ? round2(ead / totalEad * 100.0) : 0.0;
                recs.add(new CprPortfolioLine(dim[0], String.valueOf(en.getKey()), round2(ead), share));
            }
        }
        return persist(Export.Envelope.of(DownstreamSystem.CPR, "PORTFOLIO_COMPOSITION", key, VERSION, recs), asOf, actor);
    }

    // --------------------------------------------------- reads

    @Transactional(readOnly = true)
    public List<ExportBatch> list(String destination) {
        return destination == null ? batches.findAllByOrderByIdDesc()
                : batches.findByDestinationOrderByIdDesc(destination.toUpperCase());
    }

    @Transactional(readOnly = true)
    public ExportBatch get(Long id) {
        return batches.findById(id).orElseThrow(() -> ApiException.notFound("No export batch: " + id));
    }

    // --------------------------------------------------- helpers

    private ExportBatch persist(Export.Envelope<?> env, String asOf, String actor) {
        ExportBatch b = new ExportBatch();
        b.setDestination(env.destination().name());
        b.setFeedType(env.feedType());
        b.setIdempotencyKey(env.idempotencyKey());
        b.setAsOf(asOf);
        b.setRecordCount(env.recordCount());
        b.setStatus("GENERATED");
        b.setGeneratedBy(actor);
        b.setEnvelope(mapper.convertValue(env, new TypeReference<Map<String, Object>>() {
        }));
        ExportBatch saved = batches.save(b);
        audit.engine("EXPORT_GENERATED", "ExportBatch", String.valueOf(saved.getId()),
                "%s %s feed — %d record(s)".formatted(env.destination(), env.feedType(), env.recordCount()),
                Map.of("destination", env.destination().name(), "feedType", env.feedType(),
                        "records", env.recordCount(), "idempotencyKey", env.idempotencyKey()));
        return saved;
    }

    private String glAccount(String stage) {
        return switch (stage == null ? "" : stage) {
            case "STAGE_1" -> "GL-PROV-ECL-12M";
            case "STAGE_2" -> "GL-PROV-ECL-LIFETIME-PERFORMING";
            case "STAGE_3" -> "GL-PROV-ECL-LIFETIME-NPA";
            default -> "GL-PROV-ECL-UNSTAGED";
        };
    }

    private double coerce(Object v) {
        if (v instanceof Number n) return n.doubleValue();
        try {
            return v == null ? 0.0 : Double.parseDouble(String.valueOf(v));
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }

    private double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
