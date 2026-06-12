package com.helix.origination.service;

import com.helix.common.audit.AuditService;
import com.helix.common.model.Enums.ApplicationStatus;
import com.helix.common.util.References;
import com.helix.common.web.ApiException;
import com.helix.origination.dto.Dtos.AddCollateralRequest;
import com.helix.origination.dto.Dtos.AddFacilityRequest;
import com.helix.origination.dto.Dtos.AddSublimitRequest;
import com.helix.origination.dto.Dtos.CellView;
import com.helix.origination.dto.Dtos.CollateralView;
import com.helix.origination.dto.Dtos.CreateApplicationRequest;
import com.helix.origination.dto.Dtos.CreditInputs;
import com.helix.origination.dto.Dtos.DealEnvelope;
import com.helix.origination.dto.Dtos.FacilityView;
import com.helix.origination.dto.Dtos.InterchangeabilityGroupView;
import com.helix.origination.dto.Dtos.OverrideRequest;
import com.helix.origination.dto.Dtos.SublimitView;
import com.helix.origination.dto.Dtos.PeriodAnalysis;
import com.helix.origination.dto.Dtos.SpreadAnalysis;
import com.helix.origination.dto.Dtos.SpreadRequest;
import com.helix.origination.dto.Dtos.UploadDocumentRequest;
import com.helix.origination.entity.Collateral;
import com.helix.origination.entity.Document;
import com.helix.origination.entity.FinancialPeriod;
import com.helix.origination.entity.LoanApplication;
import com.helix.origination.entity.ProposedFacility;
import com.helix.origination.entity.SpreadCell;
import com.helix.origination.entity.Sublimit;
import com.helix.origination.repo.CollateralRepository;
import com.helix.origination.repo.DocumentRepository;
import com.helix.origination.repo.FinancialPeriodRepository;
import com.helix.origination.repo.LoanApplicationRepository;
import com.helix.origination.repo.ProposedFacilityRepository;
import com.helix.origination.repo.SpreadCellRepository;
import com.helix.origination.repo.SublimitRepository;

import java.util.stream.Collectors;

import java.time.LocalDate;
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
    private final ProposedFacilityRepository facilities;
    private final CollateralRepository collaterals;
    private final SublimitRepository sublimits;
    private final DocumentClassifier classifier;
    private final AuditService audit;

    public OriginationService(LoanApplicationRepository applications, DocumentRepository documents,
                              FinancialPeriodRepository periods, SpreadCellRepository cells,
                              ProposedFacilityRepository facilities, CollateralRepository collaterals,
                              SublimitRepository sublimits,
                              DocumentClassifier classifier, AuditService audit) {
        this.applications = applications;
        this.documents = documents;
        this.periods = periods;
        this.cells = cells;
        this.facilities = facilities;
        this.collaterals = collaterals;
        this.sublimits = sublimits;
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

        // The application carries a "primary" proposed facility for backward compatibility.
        // Additional facilities (term + working capital + LC line …) are added via /facilities.
        ProposedFacility primary = new ProposedFacility();
        primary.setApplicationId(saved.getId());
        primary.setReference(References.forFacility());
        primary.setOrdinal(0);
        primary.setPrimary(true);
        primary.setFacilityType(req.facilityType());
        primary.setAmount(req.requestedAmount());
        primary.setCurrency(req.currency());
        primary.setTenorMonths(req.tenorMonths());
        primary.setPurpose(req.purpose());
        facilities.save(primary);

        if (req.collateralType() != null && !req.collateralType().isBlank() && req.collateralValue() > 0) {
            Collateral c = new Collateral();
            c.setApplicationId(saved.getId());
            c.setFacilityId(primary.getId());
            c.setCollateralType(req.collateralType().toUpperCase());
            c.setDescription("Primary collateral · " + req.collateralType());
            c.setMarketValue(req.collateralValue());
            c.setHaircut(defaultHaircut(req.collateralType()));
            c.setPerfectionStatus("IN_PROGRESS");
            collaterals.save(c);
        }

        audit.human(actor, "APPLICATION_CREATED", "Application", saved.getReference(),
                "Created %s facility of %.0f %s for %s".formatted(
                        req.facilityType(), req.requestedAmount(), req.currency(), req.counterpartyName()),
                Map.of("segment", req.segment(), "jurisdiction", req.jurisdiction()));
        return saved;
    }

    private double defaultHaircut(String type) {
        return switch (type == null ? "" : type.toUpperCase()) {
            case "CASH" -> 0.0;
            case "GOVT_SECURITIES" -> 0.02;
            case "EQUITY_LISTED" -> 0.25;
            case "PROPERTY" -> 0.40;
            case "RECEIVABLES" -> 0.50;
            default -> 0.30;
        };
    }

    @Transactional(readOnly = true)
    public List<LoanApplication> list() {
        return applications.findAll();
    }

    @Transactional(readOnly = true)
    public List<LoanApplication> listByCounterparty(String counterpartyRef) {
        return applications.findByCounterpartyRef(counterpartyRef);
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

    // ------------------------------------------------------- facilities (multi)

    @Transactional
    public ProposedFacility addFacility(String reference, AddFacilityRequest req, String actor) {
        LoanApplication app = get(reference);
        List<ProposedFacility> existing = facilities.findByApplicationIdOrderByOrdinalAsc(app.getId());
        ProposedFacility f = new ProposedFacility();
        f.setApplicationId(app.getId());
        f.setReference(References.forFacility());
        f.setOrdinal(existing.size());
        f.setPrimary(existing.isEmpty());
        f.setFacilityType(req.facilityType());
        f.setAmount(req.amount());
        f.setCurrency(req.currency());
        f.setTenorMonths(req.tenorMonths());
        f.setPurpose(req.purpose());
        f.setIndicativeRate(req.indicativeRate());
        ProposedFacility saved = facilities.save(f);
        audit.human(actor, "FACILITY_ADDED", "Application", reference,
                "Added %s facility %.0f %s, tenor %dm".formatted(req.facilityType(), req.amount(),
                        req.currency(), req.tenorMonths()),
                Map.of("facilityType", req.facilityType(), "amount", req.amount()));
        return saved;
    }

    @Transactional(readOnly = true)
    public List<ProposedFacility> facilitiesFor(String reference) {
        return facilities.findByApplicationIdOrderByOrdinalAsc(get(reference).getId());
    }

    /** Enriched view including sublimits and interchangeability groups. */
    @Transactional(readOnly = true)
    public List<FacilityView> facilityViewsFor(String reference) {
        return facilities.findByApplicationIdOrderByOrdinalAsc(get(reference).getId())
                .stream().map(this::toFacilityView).toList();
    }

    /**
     * Applies an APPROVED post-sanction amendment to the facility record. Called by
     * decision-service once the DoA-routed approval completes — origination never
     * decides the amendment itself, it just owns the facility of record. Setting
     * absolute target values makes the call retry-safe (a replay is a no-op).
     */
    @Transactional
    public ProposedFacility applyAmendment(String reference, String facilityRef, Double newAmount,
                                           Integer newTenorMonths, String amendmentRef, String actor) {
        ProposedFacility f = facilities.findByApplicationIdOrderByOrdinalAsc(get(reference).getId()).stream()
                .filter(x -> facilityRef.equals(x.getReference())).findFirst()
                .orElseThrow(() -> ApiException.notFound("No facility " + facilityRef + " on " + reference));
        double oldAmount = f.getAmount();
        int oldTenor = f.getTenorMonths();
        if (newAmount != null && newAmount > 0) f.setAmount(newAmount);
        if (newTenorMonths != null && newTenorMonths > 0) f.setTenorMonths(newTenorMonths);
        ProposedFacility saved = facilities.save(f);
        audit.human(actor, "FACILITY_AMENDED", "ProposedFacility", String.valueOf(f.getId()),
                "Amended %s: amount %.2f -> %.2f, tenor %d -> %d (amendment %s)".formatted(
                        facilityRef, oldAmount, saved.getAmount(), oldTenor, saved.getTenorMonths(),
                        amendmentRef == null ? "-" : amendmentRef),
                Map.of("facilityRef", facilityRef, "oldAmount", oldAmount, "newAmount", saved.getAmount(),
                        "oldTenor", oldTenor, "newTenor", saved.getTenorMonths(),
                        "amendmentRef", amendmentRef == null ? "" : amendmentRef));
        return saved;
    }

    @Transactional
    public void removeFacility(Long facilityId, String actor) {
        ProposedFacility f = facilities.findById(facilityId)
                .orElseThrow(() -> ApiException.notFound("No facility: " + facilityId));
        if (f.isPrimary()) {
            throw ApiException.badRequest("Cannot remove the primary facility");
        }
        facilities.delete(f);
        audit.human(actor, "FACILITY_REMOVED", "ProposedFacility", String.valueOf(facilityId),
                "Removed " + f.getFacilityType(), Map.of("facilityType", f.getFacilityType()));
    }

    // ----------------------------------------------------------- collaterals

    @Transactional
    public Collateral addCollateral(String reference, AddCollateralRequest req, String actor) {
        LoanApplication app = get(reference);
        Collateral c = new Collateral();
        c.setApplicationId(app.getId());
        c.setFacilityId(req.facilityId());
        c.setCollateralType(req.collateralType().toUpperCase());
        c.setDescription(req.description());
        c.setMarketValue(req.marketValue());
        if (req.valuationDate() != null && !req.valuationDate().isBlank()) {
            c.setValuationDate(LocalDate.parse(req.valuationDate()));
        }
        c.setValuationSource(req.valuationSource());
        c.setHaircut(req.haircut() > 0 ? req.haircut() : defaultHaircut(req.collateralType()));
        c.setOwner(req.owner());
        c.setLocation(req.location());
        c.setPerfectionStatus(req.perfectionStatus());
        Collateral saved = collaterals.save(c);
        audit.human(actor, "COLLATERAL_ADDED", "Application", reference,
                "Added %s collateral %.0f (haircut %.0f%%, %s)".formatted(c.getCollateralType(),
                        c.getMarketValue(), c.getHaircut() * 100, c.getPerfectionStatus()),
                Map.of("type", c.getCollateralType(), "marketValue", c.getMarketValue()));
        return saved;
    }

    @Transactional(readOnly = true)
    public List<Collateral> collateralsFor(String reference) {
        return collaterals.findByApplicationId(get(reference).getId());
    }

    @Transactional
    public Collateral perfect(Long collateralId, String actor) {
        Collateral c = collaterals.findById(collateralId)
                .orElseThrow(() -> ApiException.notFound("No collateral: " + collateralId));
        c.setPerfectionStatus("PERFECTED");
        c.setPerfectionDate(LocalDate.now());
        audit.human(actor, "COLLATERAL_PERFECTED", "Collateral", String.valueOf(collateralId),
                "Charge perfected on %s".formatted(c.getCollateralType()), Map.of());
        return collaterals.save(c);
    }

    // --------------------------------------------------- aggregated deal view

    @Transactional(readOnly = true)
    public DealEnvelope envelope(String reference) {
        LoanApplication app = get(reference);
        List<ProposedFacility> fac = facilities.findByApplicationIdOrderByOrdinalAsc(app.getId());
        List<Collateral> cols = collaterals.findByApplicationId(app.getId());

        double totalProposed = fac.stream().mapToDouble(ProposedFacility::getAmount).sum();
        double totalCover = cols.stream().mapToDouble(Collateral::effectiveValue).sum();

        List<FacilityView> facViews = fac.stream().map(this::toFacilityView).toList();

        List<CollateralView> colViews = cols.stream().map(c -> new CollateralView(
                c.getId(), c.getFacilityId(), c.getCollateralType(), c.getDescription(),
                c.getMarketValue(), c.getHaircut(), c.effectiveValue(), c.getPerfectionStatus(),
                c.getValuationDate() == null ? null : c.getValuationDate().toString(), c.getOwner())).toList();

        Map<String, Double> latest = creditInputs(reference).latestFinancials();
        Map<String, Double> ratios = creditInputs(reference).ratios();

        return new DealEnvelope(reference, app.getCounterpartyName(), app.getJurisdiction(), app.getSegment(),
                totalProposed, app.getCurrency(), app.getTenorMonths(),
                facViews, colViews, totalCover, latest, ratios);
    }

    // ----------------------------------------------------- sublimits (multi)

    /**
     * Adds a sublimit to a facility. Enforces the rule that the sum of sublimits
     * cannot exceed the parent facility amount — protects the structuring cap.
     */
    @Transactional
    public Sublimit addSublimit(Long facilityId, AddSublimitRequest req, String actor) {
        ProposedFacility f = facilities.findById(facilityId)
                .orElseThrow(() -> ApiException.notFound("No facility: " + facilityId));
        List<Sublimit> existing = sublimits.findByFacilityIdOrderByOrdinalAsc(facilityId);
        double allocated = existing.stream().mapToDouble(Sublimit::getAmount).sum();
        if (allocated + req.amount() > f.getAmount() + 1e-6) {
            throw ApiException.badRequest(
                    "Sublimit would breach facility cap: allocated %.0f + new %.0f > facility cap %.0f"
                            .formatted(allocated, req.amount(), f.getAmount()));
        }
        if (req.currency() != null && !req.currency().equalsIgnoreCase(f.getCurrency())) {
            // We could support multi-currency sublimits in future; flag rather than allow silent drift.
            throw ApiException.badRequest("Sublimit currency must match the parent facility (" + f.getCurrency() + ")");
        }
        Sublimit s = new Sublimit();
        s.setFacilityId(facilityId);
        s.setOrdinal(existing.size());
        s.setCode(req.code().toUpperCase());
        s.setProductType(req.productType().toUpperCase());
        s.setAmount(req.amount());
        s.setCurrency(req.currency());
        s.setTenorMonths(req.tenorMonths());
        s.setPurpose(req.purpose());
        s.setInterchangeableGroup(req.interchangeableGroup() == null || req.interchangeableGroup().isBlank()
                ? null : req.interchangeableGroup().toUpperCase());
        Sublimit saved = sublimits.save(s);
        audit.human(actor, "SUBLIMIT_ADDED", "ProposedFacility", String.valueOf(facilityId),
                "Added %s sublimit %.0f%s".formatted(s.getCode(), s.getAmount(),
                        s.isFungible() ? " (group " + s.getInterchangeableGroup() + ")" : ""),
                Map.of("code", s.getCode(), "amount", s.getAmount(),
                        "interchangeableGroup", String.valueOf(s.getInterchangeableGroup())));
        return saved;
    }

    @Transactional(readOnly = true)
    public List<Sublimit> sublimitsFor(Long facilityId) {
        return sublimits.findByFacilityIdOrderByOrdinalAsc(facilityId);
    }

    @Transactional
    public void removeSublimit(Long id, String actor) {
        Sublimit s = sublimits.findById(id)
                .orElseThrow(() -> ApiException.notFound("No sublimit: " + id));
        sublimits.delete(s);
        audit.human(actor, "SUBLIMIT_REMOVED", "Sublimit", String.valueOf(id),
                "Removed " + s.getCode(), Map.of());
    }

    /** Maps a facility entity to its view, embedding sublimits and interchangeability groups. */
    private FacilityView toFacilityView(ProposedFacility f) {
        List<Sublimit> ss = sublimits.findByFacilityIdOrderByOrdinalAsc(f.getId());
        List<SublimitView> subViews = ss.stream().map(s -> new SublimitView(
                s.getId(), s.getFacilityId(), s.getOrdinal(), s.getCode(), s.getProductType(),
                s.getAmount(), s.getCurrency(), s.getTenorMonths(), s.getPurpose(),
                s.getInterchangeableGroup(), s.isFungible())).toList();

        // Group fungible sublimits by group key; each group's combined cap is the sum
        // of member amounts (utilisation can move within, capped by the combined sum).
        Map<String, List<Sublimit>> byGroup = ss.stream()
                .filter(Sublimit::isFungible)
                .collect(Collectors.groupingBy(Sublimit::getInterchangeableGroup, java.util.LinkedHashMap::new,
                        Collectors.toList()));
        List<InterchangeabilityGroupView> groupViews = byGroup.entrySet().stream()
                .map(e -> new InterchangeabilityGroupView(
                        e.getKey(),
                        e.getValue().stream().mapToDouble(Sublimit::getAmount).sum(),
                        f.getCurrency(),
                        e.getValue().stream().map(Sublimit::getCode).toList(),
                        e.getValue().size()))
                .toList();

        double sublimitTotal = ss.stream().mapToDouble(Sublimit::getAmount).sum();
        double headroom = Math.max(0.0, f.getAmount() - sublimitTotal);
        return new FacilityView(f.getId(), f.getReference(), f.getOrdinal(), f.isPrimary(),
                f.getFacilityType(), f.getAmount(), f.getCurrency(), f.getTenorMonths(),
                f.getPurpose(), f.getIndicativeRate(), subViews, groupViews, sublimitTotal, headroom);
    }
}
