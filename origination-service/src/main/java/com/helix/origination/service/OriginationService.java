package com.helix.origination.service;

import com.helix.common.audit.AuditService;
import com.helix.common.model.Enums.ApplicationStatus;
import com.helix.common.util.References;
import com.helix.common.web.ApiException;
import com.helix.origination.dto.Dtos.CellView;
import com.helix.origination.dto.Dtos.CreateApplicationRequest;
import com.helix.origination.dto.Dtos.CreditInputs;
import com.helix.origination.dto.Dtos.OverrideRequest;
import com.helix.origination.dto.Dtos.PeriodAnalysis;
import com.helix.origination.dto.Dtos.SpreadAnalysis;
import com.helix.origination.dto.Dtos.SpreadRequest;
import com.helix.origination.dto.Dtos.UploadDocumentRequest;
import com.helix.origination.entity.Document;
import com.helix.origination.entity.FinancialPeriod;
import com.helix.origination.entity.LoanApplication;
import com.helix.origination.entity.SpreadCell;
import com.helix.origination.repo.DocumentRepository;
import com.helix.origination.repo.FinancialPeriodRepository;
import com.helix.origination.repo.LoanApplicationRepository;
import com.helix.origination.repo.SpreadCellRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class OriginationService {

    /** Override beyond this fraction of the extracted value is "material" (PRD §4, US-4.1). */
    private static final double MATERIAL_THRESHOLD = 0.10;

    private final LoanApplicationRepository applications;
    private final DocumentRepository documents;
    private final FinancialPeriodRepository periods;
    private final SpreadCellRepository cells;
    private final DocumentClassifier classifier;
    private final AuditService audit;

    public OriginationService(LoanApplicationRepository applications, DocumentRepository documents,
                              FinancialPeriodRepository periods, SpreadCellRepository cells,
                              DocumentClassifier classifier, AuditService audit) {
        this.applications = applications;
        this.documents = documents;
        this.periods = periods;
        this.cells = cells;
        this.classifier = classifier;
        this.audit = audit;
    }

    // ---------------------------------------------------------------- application

    @Transactional
    public LoanApplication create(CreateApplicationRequest req, String actor) {
        LoanApplication app = new LoanApplication();
        app.setReference(References.forDeal());
        app.setCounterpartyId(req.counterpartyId());
        app.setCounterpartyRef(req.counterpartyRef());
        app.setCounterpartyName(req.counterpartyName());
        app.setJurisdiction(req.jurisdiction());
        app.setSegment(req.segment());
        app.setFacilityType(req.facilityType());
        app.setRequestedAmount(req.requestedAmount());
        app.setCurrency(req.currency());
        app.setTenorMonths(req.tenorMonths());
        app.setPurpose(req.purpose());
        app.setCollateralType(req.collateralType());
        app.setCollateralValue(req.collateralValue());
        app.setSecured(req.secured());
        app.setStatus(ApplicationStatus.INTAKE.name());
        LoanApplication saved = applications.save(app);
        audit.human(actor, "APPLICATION_CREATED", "Application", saved.getReference(),
                "Created %s facility of %.0f %s for %s".formatted(
                        req.facilityType(), req.requestedAmount(), req.currency(), req.counterpartyName()),
                Map.of("segment", req.segment(), "jurisdiction", req.jurisdiction()));
        return saved;
    }

    @Transactional(readOnly = true)
    public List<LoanApplication> list() {
        return applications.findAll();
    }

    @Transactional(readOnly = true)
    public LoanApplication get(String reference) {
        return applications.findByReference(reference)
                .orElseThrow(() -> ApiException.notFound("No application: " + reference));
    }

    @Transactional
    public LoanApplication updateStatus(String reference, String status, String actor) {
        LoanApplication app = get(reference);
        ApplicationStatus target;
        try {
            target = ApplicationStatus.valueOf(status.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("Unknown status: " + status);
        }
        String previous = app.getStatus();
        app.setStatus(target.name());
        audit.human(actor, "APPLICATION_STATUS_CHANGED", "Application", reference,
                "Status %s -> %s".formatted(previous, target), Map.of("from", previous, "to", target.name()));
        return applications.save(app);
    }

    // ---------------------------------------------------------------- documents

    @Transactional
    public Document uploadDocument(String reference, UploadDocumentRequest req, String actor) {
        LoanApplication app = get(reference);
        DocumentClassifier.Classification c = classifier.classify(req.fileName(), req.declaredType());
        Document doc = new Document();
        doc.setApplicationId(app.getId());
        doc.setFileName(req.fileName());
        doc.setDeclaredType(req.declaredType());
        doc.setClassifiedType(c.type().name());
        doc.setClassificationConfidence(c.confidence());
        doc.setNeedsReview(c.confidence() < DocumentClassifier.AUTO_ROUTE_THRESHOLD);
        Document saved = documents.save(doc);
        audit.ai("document-intelligence", "DOCUMENT_CLASSIFIED", "Application", reference,
                "Classified %s as %s (%.2f)%s".formatted(req.fileName(), c.type(), c.confidence(),
                        saved.isNeedsReview() ? " — routed to review" : " — auto-routed"),
                Map.of("type", c.type().name(), "confidence", c.confidence(), "needsReview", saved.isNeedsReview()));
        return saved;
    }

    @Transactional(readOnly = true)
    public List<Document> documents(String reference) {
        return documents.findByApplicationId(get(reference).getId());
    }

    @Transactional
    public Document verifyDocument(Long docId, String actor) {
        Document doc = documents.findById(docId)
                .orElseThrow(() -> ApiException.notFound("No document: " + docId));
        doc.setVerified(true);
        doc.setVerifiedBy(actor);
        doc.setNeedsReview(false);
        return documents.save(doc);
    }

    // ---------------------------------------------------------------- spreading

    @Transactional
    public SpreadAnalysis spread(String reference, SpreadRequest req, String actor) {
        LoanApplication app = get(reference);
        // Replace any prior spread.
        List<FinancialPeriod> existing = periods.findByApplicationIdOrderByOrdinalAsc(app.getId());
        existing.forEach(p -> cells.deleteAll(cells.findByPeriodId(p.getId())));
        periods.deleteAll(existing);

        int ordinal = 0;
        int lineCount = 0;
        for (var p : req.periods()) {
            FinancialPeriod period = new FinancialPeriod();
            period.setApplicationId(app.getId());
            period.setLabel(p.label());
            period.setGaap(p.gaap());
            period.setCurrency(p.currency());
            period.setOrdinal(ordinal++);
            FinancialPeriod savedPeriod = periods.save(period);

            Map<String, Double> values = new LinkedHashMap<>();
            for (var line : p.lines().entrySet()) {
                String key = line.getKey();
                var in = line.getValue();
                values.put(key, in.value());
                SpreadCell cell = new SpreadCell();
                cell.setPeriodId(savedPeriod.getId());
                cell.setApplicationId(app.getId());
                cell.setTaxonomyKey(key);
                cell.setLabel(CanonicalTaxonomy.label(key));
                cell.setDerived(false);
                cell.setExtractedValue(in.value());
                cell.setValue(in.value());
                cell.setConfidence(in.confidence() == null ? 0.9 : in.confidence());
                cell.setSourceDocument(in.sourceDocument());
                cell.setSourcePage(in.sourcePage());
                cell.setSourceCoordinates(in.coordinates());
                cells.save(cell);
                lineCount++;
            }
            // Derived lines are computed deterministically and itemised, never silently merged.
            for (var d : CanonicalTaxonomy.deriveAll(values).entrySet()) {
                SpreadCell cell = new SpreadCell();
                cell.setPeriodId(savedPeriod.getId());
                cell.setApplicationId(app.getId());
                cell.setTaxonomyKey(d.getKey());
                cell.setLabel(CanonicalTaxonomy.label(d.getKey()));
                cell.setDerived(true);
                cell.setExtractedValue(d.getValue());
                cell.setValue(d.getValue());
                cell.setConfidence(1.0);
                cells.save(cell);
            }
        }
        app.setSpreadConfirmed(false);
        app.setStatus(ApplicationStatus.SPREADING.name());
        applications.save(app);

        audit.ai("financial-spreading", "SPREAD_GENERATED", "Application", reference,
                "Spread %d period(s), %d source lines into the canonical chart".formatted(req.periods().size(), lineCount),
                Map.of("periods", req.periods().size(), "sourceLines", lineCount));
        return analysis(reference);
    }

    @Transactional
    public CellView overrideCell(Long cellId, OverrideRequest req, String actor) {
        SpreadCell cell = cells.findById(cellId)
                .orElseThrow(() -> ApiException.notFound("No spread cell: " + cellId));
        if (cell.isDerived()) {
            throw ApiException.badRequest("Derived lines cannot be overridden directly; override their inputs");
        }
        double extracted = cell.getExtractedValue();
        boolean material = extracted == 0.0
                ? req.value() != 0.0
                : Math.abs(req.value() - extracted) / Math.abs(extracted) > MATERIAL_THRESHOLD;
        if (material && (req.reason() == null || req.reason().isBlank())) {
            throw ApiException.badRequest("A material override (> %.0f%%) requires a reason".formatted(MATERIAL_THRESHOLD * 100));
        }
        cell.setOverridden(true);
        cell.setOverrideValue(req.value());
        cell.setOverrideReason(req.reason());
        cell.setMaterialOverride(material);
        cell.setOverriddenBy(actor);
        cell.setValue(req.value());        // effective value used downstream
        cells.save(cell);

        recomputeDerived(cell.getPeriodId());

        if (material) {
            // Material change invalidates prior confirmation — re-confirmation required (PRD §4 HITL gate).
            LoanApplication app = applications.findById(cell.getApplicationId()).orElseThrow();
            app.setSpreadConfirmed(false);
            applications.save(app);
        }
        audit.human(actor, "SPREAD_CELL_OVERRIDDEN", "SpreadCell", String.valueOf(cellId),
                "%s overridden %.2f -> %.2f%s".formatted(cell.getTaxonomyKey(), extracted, req.value(),
                        material ? " (MATERIAL — re-confirmation required)" : ""),
                Map.of("taxonomyKey", cell.getTaxonomyKey(), "material", material, "from", extracted, "to", req.value()));
        return toView(cell);
    }

    private void recomputeDerived(Long periodId) {
        List<SpreadCell> periodCells = cells.findByPeriodId(periodId);
        Map<String, Double> values = new LinkedHashMap<>();
        for (SpreadCell c : periodCells) {
            if (!c.isDerived()) {
                values.put(c.getTaxonomyKey(), c.getValue());
            }
        }
        Map<String, Double> derived = CanonicalTaxonomy.deriveAll(values);
        for (SpreadCell c : periodCells) {
            if (c.isDerived() && derived.containsKey(c.getTaxonomyKey())) {
                c.setValue(derived.get(c.getTaxonomyKey()));
                cells.save(c);
            }
        }
    }

    @Transactional
    public LoanApplication confirmSpread(String reference, String actor) {
        LoanApplication app = get(reference);
        if (periods.findByApplicationIdOrderByOrdinalAsc(app.getId()).isEmpty()) {
            throw ApiException.badRequest("Nothing to confirm — no spread exists for this application");
        }
        app.setSpreadConfirmed(true);
        applications.save(app);
        audit.human(actor, "SPREAD_CONFIRMED", "Application", reference,
                "Analyst confirmed the spread; it may now feed rating, capital and pricing", Map.of());
        return app;
    }

    // ---------------------------------------------------------------- analysis

    @Transactional(readOnly = true)
    public SpreadAnalysis analysis(String reference) {
        LoanApplication app = get(reference);
        List<FinancialPeriod> ps = periods.findByApplicationIdOrderByOrdinalAsc(app.getId());
        List<PeriodAnalysis> periodViews = new ArrayList<>();
        Map<Integer, Map<String, Double>> byOrdinal = new LinkedHashMap<>();

        for (FinancialPeriod p : ps) {
            List<SpreadCell> periodCells = cells.findByPeriodId(p.getId());
            Map<String, Double> values = new LinkedHashMap<>();
            List<CellView> views = new ArrayList<>();
            // Order: input lines then derived, following the canonical chart.
            for (String key : CanonicalTaxonomy.INPUT_LINES) {
                periodCells.stream().filter(c -> c.getTaxonomyKey().equals(key)).findFirst()
                        .ifPresent(c -> {
                            values.put(c.getTaxonomyKey(), c.getValue());
                            views.add(toView(c));
                        });
            }
            for (String key : CanonicalTaxonomy.DERIVED_LINES) {
                periodCells.stream().filter(c -> c.getTaxonomyKey().equals(key)).findFirst()
                        .ifPresent(c -> {
                            values.put(c.getTaxonomyKey(), c.getValue());
                            views.add(toView(c));
                        });
            }
            byOrdinal.put(p.getOrdinal(), values);
            periodViews.add(new PeriodAnalysis(p.getId(), p.getLabel(), p.getGaap(), p.getCurrency(),
                    views, Ratios.compute(values)));
        }

        Map<String, Double> trends = trends(byOrdinal);
        List<String> flags = benchmarkFlags(byOrdinal.get(0));
        return new SpreadAnalysis(reference, app.isSpreadConfirmed(), periodViews, trends, flags);
    }

    @Transactional(readOnly = true)
    public CreditInputs creditInputs(String reference) {
        LoanApplication app = get(reference);
        List<FinancialPeriod> ps = periods.findByApplicationIdOrderByOrdinalAsc(app.getId());
        Map<String, Double> latest = new LinkedHashMap<>();
        Map<Integer, Map<String, Double>> byOrdinal = new LinkedHashMap<>();
        for (FinancialPeriod p : ps) {
            Map<String, Double> values = new LinkedHashMap<>();
            for (SpreadCell c : cells.findByPeriodId(p.getId())) {
                values.put(c.getTaxonomyKey(), c.getValue());
            }
            byOrdinal.put(p.getOrdinal(), values);
            if (p.getOrdinal() == 0) {
                latest = values;
            }
        }
        return new CreditInputs(app.getReference(), app.getCounterpartyId(), app.getCounterpartyRef(),
                app.getCounterpartyName(), app.getJurisdiction(), app.getSegment(), app.getFacilityType(),
                app.getRequestedAmount(), app.getCurrency(), app.getTenorMonths(), app.getCollateralType(),
                app.getCollateralValue(), app.isSecured(), app.isSpreadConfirmed(),
                latest, Ratios.compute(latest), trends(byOrdinal));
    }

    private Map<String, Double> trends(Map<Integer, Map<String, Double>> byOrdinal) {
        Map<String, Double> trends = new LinkedHashMap<>();
        Map<String, Double> latest = byOrdinal.get(0);
        Map<String, Double> prior = byOrdinal.get(1);
        if (latest != null && prior != null) {
            trends.put("REVENUE_GROWTH", growth(latest.get("REVENUE"), prior.get("REVENUE")));
            trends.put("EBITDA_GROWTH", growth(latest.get("EBITDA"), prior.get("EBITDA")));
            trends.put("DEBT_GROWTH", growth(latest.get("TOTAL_DEBT"), prior.get("TOTAL_DEBT")));
        }
        return trends;
    }

    private double growth(Double now, Double before) {
        if (now == null || before == null || Math.abs(before) < 0.0001) {
            return 0.0;
        }
        return Math.round((now - before) / Math.abs(before) * 1000.0) / 1000.0;
    }

    /** Lightweight peer/policy benchmarking, flagging outliers (PRD §4, US-4.3). */
    private List<String> benchmarkFlags(Map<String, Double> latest) {
        List<String> flags = new ArrayList<>();
        if (latest == null) {
            return flags;
        }
        Map<String, Double> ratios = Ratios.compute(latest);
        if (ratios.getOrDefault("NET_LEVERAGE", 0.0) > 3.5) {
            flags.add("Net leverage %.1fx exceeds 3.5x benchmark".formatted(ratios.get("NET_LEVERAGE")));
        }
        if (ratios.getOrDefault("DSCR", 99.0) < 1.25) {
            flags.add("DSCR %.2f below the 1.25x covenant norm".formatted(ratios.get("DSCR")));
        }
        if (ratios.getOrDefault("INTEREST_COVERAGE", 99.0) < 2.0) {
            flags.add("Interest coverage %.2f below 2.0x".formatted(ratios.get("INTEREST_COVERAGE")));
        }
        if (ratios.getOrDefault("CURRENT_RATIO", 0.0) < 1.0) {
            flags.add("Current ratio %.2f below 1.0 — liquidity pressure".formatted(ratios.get("CURRENT_RATIO")));
        }
        if (ratios.getOrDefault("EBITDA_MARGIN", 0.0) < 0.10) {
            flags.add("EBITDA margin %.1f%% below peer median".formatted(ratios.get("EBITDA_MARGIN") * 100));
        }
        return flags;
    }

    private CellView toView(SpreadCell c) {
        return new CellView(c.getId(), c.getTaxonomyKey(), c.getLabel(), c.isDerived(), c.getValue(),
                c.getExtractedValue(), c.getConfidence(), c.getSourceDocument(), c.getSourcePage(),
                c.getSourceCoordinates(), c.isOverridden(), c.getOverrideValue(), c.getOverrideReason(),
                c.isMaterialOverride(), c.getOverriddenBy());
    }
}
