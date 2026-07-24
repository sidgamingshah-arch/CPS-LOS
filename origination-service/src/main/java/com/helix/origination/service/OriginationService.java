package com.helix.origination.service;

import com.helix.common.audit.AuditService;
import com.helix.common.dms.DocumentStoreService;
import com.helix.common.dms.StoredDocument;
import com.helix.common.fieldpolicy.FieldPolicyService;
import com.helix.common.llm.LlmClient;
import com.helix.common.llm.LlmRequest;
import com.helix.common.llm.LlmResult;
import com.helix.common.model.Enums.ApplicationStatus;
import com.helix.common.model.Enums.DocumentType;
import com.helix.common.util.References;
import com.helix.common.web.ApiException;
import com.helix.common.workflow.WorkflowClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import com.helix.origination.dto.Dtos.AddCollateralRequest;
import com.helix.origination.dto.Dtos.AddFacilityRequest;
import com.helix.origination.dto.Dtos.AddSublimitRequest;
import com.helix.origination.dto.Dtos.CellView;
import com.helix.origination.dto.Dtos.CollateralView;
import com.helix.origination.dto.Dtos.CreateApplicationRequest;
import com.helix.origination.dto.Dtos.CreditCollateral;
import com.helix.origination.dto.Dtos.CreditFacility;
import com.helix.origination.dto.Dtos.CreditInputs;
import com.helix.origination.dto.Dtos.DealEnvelope;
import com.helix.origination.dto.Dtos.FacilityView;
import com.helix.origination.dto.Dtos.InterchangeabilityGroupView;
import com.helix.origination.dto.Dtos.OverrideRequest;
import com.helix.origination.dto.Dtos.SublimitView;
import com.helix.origination.dto.Dtos.PeriodAnalysis;
import com.helix.origination.dto.Dtos.SpreadAnalysis;
import com.helix.origination.dto.Dtos.SpreadFromExtractionRequest;
import com.helix.origination.dto.Dtos.SpreadRequest;
import com.helix.origination.dto.Dtos.SpreadVersionDetail;
import com.helix.origination.dto.Dtos.SpreadVersionView;
import com.helix.origination.dto.Dtos.UpdateCollateralRequest;
import com.helix.origination.dto.Dtos.UpdateFacilityRequest;
import com.helix.origination.dto.Dtos.UpdateSublimitRequest;
import com.helix.origination.dto.Dtos.UploadDocumentRequest;
import com.helix.origination.entity.Collateral;
import com.helix.origination.entity.DocExtraction;
import com.helix.origination.entity.Document;
import com.helix.origination.entity.FinancialPeriod;
import com.helix.origination.entity.LoanApplication;
import com.helix.origination.entity.ProposedFacility;
import com.helix.origination.entity.SpreadCell;
import com.helix.origination.entity.SpreadVersion;
import com.helix.origination.entity.Sublimit;
import com.helix.origination.repo.CollateralRepository;
import com.helix.origination.repo.DocExtractionRepository;
import com.helix.origination.repo.DocumentRepository;
import com.helix.origination.repo.FinancialPeriodRepository;
import com.helix.origination.repo.LoanApplicationRepository;
import com.helix.origination.repo.ProposedFacilityRepository;
import com.helix.origination.repo.SpreadCellRepository;
import com.helix.origination.repo.SpreadVersionRepository;
import com.helix.origination.repo.SublimitRepository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.stream.Collectors;

import java.time.Instant;
import java.time.LocalDate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class OriginationService {

    private static final Logger log = LoggerFactory.getLogger(OriginationService.class);

    /** Override beyond this fraction of the extracted value is "material" (PRD §4, US-4.1). */
    private static final double MATERIAL_THRESHOLD = 0.10;

    /** Allowed origin markers on the spread version timeline. */
    private static final Set<String> SPREAD_VERSION_SOURCES = Set.of("MANUAL", "DOC_INTEL", "RESUBMISSION");

    /**
     * The deal STRUCTURE (facilities / collaterals / sublimits) may be freely edited or deleted only
     * during the pre-sanction structuring phase. Once the proposal is submitted for approval or
     * sanctioned, the structure of record is frozen and any change must go through the governed
     * post-sanction amendment path ({@link #applyAmendment}) — so edits are rejected past these
     * statuses with a 409. (PENDING_APPROVAL and everything after is out of bounds.)
     */
    private static final Set<String> STRUCTURE_EDITABLE_STATUSES = Set.of(
            ApplicationStatus.DRAFT.name(), ApplicationStatus.INTAKE.name(),
            ApplicationStatus.SPREADING.name(), ApplicationStatus.RATING.name(),
            ApplicationStatus.CAPITAL.name(), ApplicationStatus.PRICING.name());

    private final LoanApplicationRepository applications;
    private final DocumentRepository documents;
    private final FinancialPeriodRepository periods;
    private final SpreadCellRepository cells;
    private final ProposedFacilityRepository facilities;
    private final CollateralRepository collaterals;
    private final SublimitRepository sublimits;
    private final SpreadVersionRepository spreadVersions;
    private final DocExtractionRepository extractions;
    private final DocumentClassifier classifier;
    /** Governed DMS lane (helix-common) — stores the raw uploaded bytes + sha256/metadata. */
    private final DocumentStoreService documentStore;
    /** Real text extraction (PDFBox / UTF-8 / config-gated OCR) from the uploaded bytes. */
    private final TextExtractionService textExtraction;
    private final AuditService audit;
    /** The app's Jackson mapper — serialises the append-only spread version snapshots. */
    private final ObjectMapper json;
    /** Optional best-effort hook into workflow-service; bean is absent when the URL isn't configured. */
    private final WorkflowClient workflow;
    /** Level-1 financial-analysis currency normalisation source (shared FX table with limit-service). */
    private final com.helix.origination.client.FxRatesClient fx;
    /** Configurable chart-of-accounts augmentation (extra input/derived lines + ratios). */
    private final com.helix.origination.client.FinancialTemplateClient financialTemplates;
    /** Borrower sector lookup (counterparty-service) — pinned on the application at create. */
    private final com.helix.origination.client.CounterpartyClient counterparties;
    /** Governed LLM SPI (default {@code none} → deterministic fallback everywhere). */
    private final LlmClient llm;
    /** Config-driven dynamic screen behaviour (FIELD_POLICY). Conditional bean — present only
     *  when helix.config-service.base-url is set — so it is read via ObjectProvider and is a
     *  no-op when absent / on a config outage (fail-open; default byte-identical to today). */
    private final ObjectProvider<FieldPolicyService> fieldPolicy;

    public OriginationService(LoanApplicationRepository applications, DocumentRepository documents,
                              FinancialPeriodRepository periods, SpreadCellRepository cells,
                              ProposedFacilityRepository facilities, CollateralRepository collaterals,
                              SublimitRepository sublimits, SpreadVersionRepository spreadVersions,
                              DocExtractionRepository extractions,
                              DocumentClassifier classifier,
                              DocumentStoreService documentStore, TextExtractionService textExtraction,
                              AuditService audit, ObjectMapper json,
                              com.helix.origination.client.FxRatesClient fx,
                              com.helix.origination.client.FinancialTemplateClient financialTemplates,
                              com.helix.origination.client.CounterpartyClient counterparties,
                              LlmClient llm,
                              ObjectProvider<FieldPolicyService> fieldPolicy,
                              @Autowired(required = false) WorkflowClient workflow) {
        this.counterparties = counterparties;
        this.llm = llm;
        this.fieldPolicy = fieldPolicy;
        this.applications = applications;
        this.documents = documents;
        this.periods = periods;
        this.cells = cells;
        this.facilities = facilities;
        this.collaterals = collaterals;
        this.sublimits = sublimits;
        this.spreadVersions = spreadVersions;
        this.extractions = extractions;
        this.classifier = classifier;
        this.documentStore = documentStore;
        this.textExtraction = textExtraction;
        this.financialTemplates = financialTemplates;
        this.audit = audit;
        this.json = json;
        this.fx = fx;
        this.workflow = workflow;
    }

    // ---------------------------------------------------------------- application

    @Transactional
    public LoanApplication create(CreateApplicationRequest req, String actor) {
        // Config-driven dynamic screen behaviour: enforce the ORIGINATION_APPLICATION field
        // policy (conditional-required + static-required) server-side BEFORE persisting. This is
        // the authoritative gate — client-side visibility is convenience only and cannot bypass
        // it. Fail-open: no bean / no FIELD_POLICY master / config outage ⇒ no-op (today's behaviour).
        FieldPolicyService fp = fieldPolicy.getIfAvailable();
        if (fp != null) {
            Map<String, Object> submitted = new LinkedHashMap<>();
            submitted.put("facilityType", req.facilityType());
            submitted.put("requestedAmount", req.requestedAmount());
            submitted.put("currency", req.currency());
            submitted.put("tenorMonths", req.tenorMonths());
            submitted.put("purpose", req.purpose());
            submitted.put("collateralType", req.collateralType());
            submitted.put("collateralValue", req.collateralValue());
            submitted.put("secured", req.secured());
            fp.enforce("ORIGINATION_APPLICATION", submitted);
        }

        LoanApplication app = new LoanApplication();
        app.setReference(References.forDeal());
        app.setCounterpartyId(req.counterpartyId());
        app.setCounterpartyRef(req.counterpartyRef());
        app.setCounterpartyName(req.counterpartyName());
        app.setJurisdiction(req.jurisdiction());
        app.setSegment(req.segment());
        // Pin the borrower's sector so sector-specific templates resolve downstream.
        app.setSector(counterparties.sectorFor(req.counterpartyRef()));
        app.setFacilityType(req.facilityType());
        app.setRequestedAmount(req.requestedAmount());
        app.setCurrency(req.currency());
        app.setTenorMonths(req.tenorMonths());
        app.setPurpose(req.purpose());
        app.setCollateralType(req.collateralType());
        app.setCollateralValue(req.collateralValue() == null ? 0.0 : req.collateralValue());
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

        if (req.collateralType() != null && !req.collateralType().isBlank()
                && req.collateralValue() != null && req.collateralValue() > 0) {
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
        // Best-effort lifecycle materialisation in workflow-service. Outage / mismatched
        // pack must never break origination — the WorkflowClient swallows + logs.
        if (workflow != null) {
            workflow.materialise(saved.getReference(), req.jurisdiction(), req.segment(), actor);
        }
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
        // The type LABEL and confidence come from the deterministic keyword classifier. When the
        // classifier is UNSURE (confidence below the auto-route threshold — i.e. the document is
        // already routed to human review) and an external model is configured, the model may
        // suggest a better label from the EXISTING DocumentType set. The confidence and the
        // needs-review routing stay 100% deterministic; the model only ever picks an allowed
        // label. Provider 'none' (default) or an invalid/unusable reply → the keyword label,
        // byte-identical to today.
        String classifiedType = c.type().name();
        boolean llmDrafted = false;
        String llmModel = null;
        if (c.confidence() < DocumentClassifier.AUTO_ROUTE_THRESHOLD) {
            LlmResult r = llmClassify(req.fileName(), req.declaredType(), c.type());
            if (r.usable()) {
                DocumentType picked = parseDocumentType(r.text());
                if (picked != null) {
                    classifiedType = picked.name();
                    llmDrafted = true;
                    llmModel = r.model();
                }
            }
        }
        Document doc = new Document();
        doc.setApplicationId(app.getId());
        doc.setFileName(req.fileName());
        doc.setDeclaredType(req.declaredType());
        doc.setClassifiedType(classifiedType);
        doc.setClassificationConfidence(c.confidence());
        doc.setNeedsReview(c.confidence() < DocumentClassifier.AUTO_ROUTE_THRESHOLD);
        Document saved = documents.save(doc);
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("type", classifiedType);
        detail.put("confidence", c.confidence());
        detail.put("needsReview", saved.isNeedsReview());
        if (llmDrafted) {
            detail.put("llmDrafted", true);
            detail.put("llmModel", llmModel);
        }
        audit.ai("document-intelligence", "DOCUMENT_CLASSIFIED", "Application", reference,
                "Classified %s as %s (%.2f)%s".formatted(req.fileName(), classifiedType, c.confidence(),
                        saved.isNeedsReview() ? " — routed to review" : " — auto-routed"),
                detail);
        return saved;
    }

    /**
     * REAL file upload for document capture. Unlike {@link #uploadDocument} (a filename-only
     * record kept for the seed + e2e_smoke), this accepts the actual file bytes:
     * <ol>
     *   <li>stores the bytes in the governed DMS ({@link DocumentStoreService#store}) — captures
     *       the DMS id + content sha256 (the DMS itself stamps {@code DOCUMENT_STORED});</li>
     *   <li>extracts the document's real text via {@link TextExtractionService} (PDFBox / UTF-8 /
     *       config-gated OCR) — fail-soft, never throws;</li>
     *   <li>classifies from BOTH the filename AND the extracted text (content wins when the name
     *       is unhelpful);</li>
     *   <li>persists the {@link Document} row (classification exactly as the legacy path, plus the
     *       real-capture columns) and stamps a HUMAN {@code DOCUMENT_UPLOADED} audit event.</li>
     * </ol>
     * The extracted text is later used by doc-intelligence to derive fields FROM the document; the
     * governance invariant is untouched — extraction stays a SUGGESTED, human-confirmed advisory
     * that never writes an authoritative figure.
     */
    @Transactional
    public Document uploadDocumentFile(String reference, org.springframework.web.multipart.MultipartFile file,
                                       String declaredType, String actor) {
        LoanApplication app = get(reference);
        if (file == null || file.isEmpty()) {
            throw ApiException.badRequest("file part is required");
        }
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (java.io.IOException e) {
            throw ApiException.badRequest("could not read uploaded file: " + e.getMessage());
        }
        String fileName = file.getOriginalFilename() == null || file.getOriginalFilename().isBlank()
                ? "upload.bin" : file.getOriginalFilename();
        String contentType = file.getContentType();

        // 1) Store the raw bytes in the governed DMS (sha256 + backend + opaque key). This is
        //    deterministic byte storage; it stamps its own DOCUMENT_STORED HUMAN audit event.
        StoredDocument stored = documentStore.store("Application", reference, fileName, contentType, bytes, actor);

        // 2) Extract the document's REAL text (fail-soft: any error -> empty text + a note).
        TextExtractionService.ExtractedText extracted =
                textExtraction.extract(fileName, contentType, bytes);

        // 3) Classify from BOTH the filename AND the extracted content.
        DocumentClassifier.Classification c = classifier.classify(fileName, declaredType, extracted.text());
        String classifiedType = c.type().name();
        boolean llmDrafted = false;
        String llmModel = null;
        if (c.confidence() < DocumentClassifier.AUTO_ROUTE_THRESHOLD) {
            LlmResult r = llmClassify(fileName, declaredType, c.type());
            if (r.usable()) {
                DocumentType picked = parseDocumentType(r.text());
                if (picked != null) {
                    classifiedType = picked.name();
                    llmDrafted = true;
                    llmModel = r.model();
                }
            }
        }

        // 4) Persist the Document row (classification as the legacy path + the real-capture columns).
        Document doc = new Document();
        doc.setApplicationId(app.getId());
        doc.setFileName(fileName);
        doc.setDeclaredType(declaredType);
        doc.setClassifiedType(classifiedType);
        doc.setClassificationConfidence(c.confidence());
        doc.setNeedsReview(c.confidence() < DocumentClassifier.AUTO_ROUTE_THRESHOLD);
        doc.setStoredDocId(stored.getId());
        doc.setExtractedText(extracted.text() == null || extracted.text().isBlank() ? null : extracted.text());
        doc.setExtractionMethod(extracted.method());
        doc.setOcrUsed(extracted.ocrUsed());
        doc.setPageCount(extracted.pageCount());
        doc.setSha256(stored.getSha256());
        doc.setSizeBytes((long) bytes.length);
        doc.setContentType(stored.getContentType());
        Document saved = documents.save(doc);

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("storedDocId", stored.getId());
        detail.put("sha256", stored.getSha256());
        detail.put("sizeBytes", bytes.length);
        detail.put("extractionMethod", extracted.method());
        detail.put("pageCount", extracted.pageCount());
        detail.put("ocrUsed", extracted.ocrUsed());
        detail.put("classifiedType", classifiedType);
        detail.put("confidence", c.confidence());
        detail.put("needsReview", saved.isNeedsReview());
        if (extracted.note() != null) {
            detail.put("extractionNote", extracted.note());
        }
        if (llmDrafted) {
            detail.put("llmDrafted", true);
            detail.put("llmModel", llmModel);
        }
        // HUMAN accountability for the upload action (the DMS store already stamped DOCUMENT_STORED).
        audit.human(actor, "DOCUMENT_UPLOADED", "Application", reference,
                "Uploaded '%s' (%d bytes, %s%s) classified %s (%.2f)%s".formatted(
                        fileName, bytes.length, extracted.method(),
                        extracted.pageCount() > 0 ? ", " + extracted.pageCount() + "p" : "",
                        classifiedType, c.confidence(),
                        saved.isNeedsReview() ? " — routed to review" : " — auto-routed"),
                detail);
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

        // ---- Level-1 currency: resolve the presentation currency every period is
        // normalised into. Explicit on the request, else the latest period's
        // native currency (request order: index 0 = latest).
        String presentationCurrency = req.presentationCurrency() != null && !req.presentationCurrency().isBlank()
                ? req.presentationCurrency().toUpperCase()
                : (req.periods().isEmpty() ? null
                        : req.periods().get(0).currency() == null ? null
                        : req.periods().get(0).currency().toUpperCase());

        // Resolve the configurable chart-of-accounts augmentation for this deal (extra
        // lines/ratios on top of the canonical chart). EMPTY when none/unreachable.
        com.helix.origination.client.FinancialTemplateClient.FinancialTemplate tmpl =
                financialTemplates.resolve(app.getJurisdiction(), app.getSector(), app.getSegment());

        int ordinal = 0;
        int lineCount = 0;
        for (var p : req.periods()) {
            String nativeCcy = p.currency() == null ? null : p.currency().toUpperCase();
            // Resolve the native->presentation rate, in priority order:
            //   1. explicit analyst rate (SUPPLIED)
            //   2. 1.0 when currencies match (SAME_CURRENCY)
            //   3. the dated FX_RATE master as at the period-end (DATED_MASTER)
            //   4. the current spot table (CURRENT_SPOT)
            // If a foreign period has no resolvable rate we FAIL the spread rather
            // than silently treating it as 1.0 — the currency-consistency guard.
            Double fxToPres;
            String fxSource;
            if (p.fxToPresentation() != null) {
                if (p.fxToPresentation() <= 0) {
                    throw ApiException.badRequest("fxToPresentation must be positive for period " + p.label());
                }
                fxToPres = p.fxToPresentation();
                fxSource = "SUPPLIED";
            } else if (nativeCcy != null && nativeCcy.equalsIgnoreCase(presentationCurrency)) {
                fxToPres = 1.0;
                fxSource = "SAME_CURRENCY";
            } else {
                Double dated = null;
                java.time.LocalDate asOf = parsePeriodEnd(p.periodEnd());
                if (fx != null && asOf != null) {
                    dated = fx.crossRateAsOf(nativeCcy, presentationCurrency, asOf);
                }
                if (dated != null) {
                    fxToPres = dated;
                    fxSource = "DATED_MASTER";
                } else {
                    Double spot = fx == null ? null : fx.crossRate(nativeCcy, presentationCurrency);
                    if (spot == null) {
                        throw ApiException.badRequest(
                                ("Period %s is in %s but the spread presentation currency is %s and no FX rate "
                                + "is available (supply fxToPresentation, add a dated FX_RATE master point on/before "
                                + "%s, or ensure %s->%s is in the spot FX table) — refusing to mix currencies silently")
                                .formatted(p.label(), nativeCcy, presentationCurrency,
                                        p.periodEnd() == null ? "the period-end" : p.periodEnd(),
                                        nativeCcy, presentationCurrency));
                    }
                    fxToPres = spot;
                    fxSource = "CURRENT_SPOT";
                }
            }

            FinancialPeriod period = new FinancialPeriod();
            period.setApplicationId(app.getId());
            period.setLabel(p.label());
            period.setGaap(p.gaap());
            period.setCurrency(p.currency());
            period.setPresentationCurrency(presentationCurrency);
            period.setFxToPresentation(fxToPres);
            period.setFxRateSource(fxSource);
            period.setPeriodEnd(p.periodEnd());
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
            Map<String, Double> derived = CanonicalTaxonomy.deriveAll(values);
            for (var d : derived.entrySet()) {
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
            // Template extra derived lines — formulas over the canonical inputs + derived
            // (+ any template extra input lines that arrived in this period's data).
            Map<String, Double> all = new LinkedHashMap<>(values);
            all.putAll(derived);
            for (var d : tmpl.extraDerived()) {
                if (d.key() == null || d.formula() == null) continue;
                double v = com.helix.common.formula.FormulaEvaluator.eval(d.formula(), all);
                all.put(d.key(), v);
                SpreadCell cell = new SpreadCell();
                cell.setPeriodId(savedPeriod.getId());
                cell.setApplicationId(app.getId());
                cell.setTaxonomyKey(d.key());
                cell.setLabel(d.label() == null ? d.key() : d.label());
                cell.setDerived(true);
                cell.setExtractedValue(v);
                cell.setValue(v);
                cell.setConfidence(1.0);
                cells.save(cell);
            }
        }
        app.setSpreadConfirmed(false);
        app.setStatus(ApplicationStatus.SPREADING.name());
        applications.save(app);

        boolean multiCurrency = req.periods().stream()
                .map(pp -> pp.currency() == null ? "" : pp.currency().toUpperCase())
                .distinct().count() > 1;
        // Advisory pre-confirm LLM suggestion of candidate line items. When an external model is
        // configured it drafts a candidate extraction from the SAME analyst-supplied source lines.
        // This is advisory ONLY: it is never written into the persisted cells, the deterministic
        // ratios, or the returned SpreadAnalysis, and the analyst's confirm gate is unchanged — the
        // suggestion is recorded on the audit trail. Provider 'none' (default) → no call, byte-identical.
        boolean llmDrafted = false;
        String llmModel = null;
        Integer aiCandidateLines = null;
        if (!req.periods().isEmpty()) {
            LlmResult r = llmSpreadingSuggest(reference, req);
            if (r.usable()) {
                llmDrafted = true;
                llmModel = r.model();
                Map<String, Object> parsed = parseJsonObject(r.text());
                aiCandidateLines = parsed == null ? null : parsed.size();
            }
        }
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("periods", req.periods().size());
        detail.put("sourceLines", lineCount);
        detail.put("presentationCurrency", presentationCurrency == null ? "" : presentationCurrency);
        detail.put("multiCurrency", multiCurrency);
        if (llmDrafted) {
            detail.put("llmDrafted", true);
            detail.put("llmModel", llmModel);
            if (aiCandidateLines != null) {
                detail.put("aiCandidateLines", aiCandidateLines);
            }
        }
        audit.ai("financial-spreading", "SPREAD_GENERATED", "Application", reference,
                "Spread %d period(s), %d source lines into the canonical chart%s".formatted(
                        req.periods().size(), lineCount,
                        multiCurrency ? " (normalised to " + presentationCurrency + ")" : ""),
                detail);
        SpreadAnalysis result = analysis(reference);
        recordSpreadVersion(app, reference, req, result, actor);
        return result;
    }

    // ------------------------------------------------- AI-extract -> grid -> human-confirm

    /**
     * Pre-fills a DRAFT spread from a CONFIRMED {@link DocExtraction} — the AI-EXTRACT → GRID →
     * HUMAN-CONFIRM link made real and honest. It maps the extraction's recognised figure fields
     * onto canonical INPUT taxonomy keys and rebuilds the working spread as an unconfirmed DRAFT
     * (source DOC_INTEL on the version timeline).
     * <p>CRITICAL GOVERNANCE: this NEVER confirms the spread — {@link #spread} resets the
     * confirm-gate to {@code false}, so the authoritative figure path (rating / capital / pricing
     * read the CONFIRMED spread) is untouched until the analyst separately calls
     * {@link #confirmSpread}. Derived lines (EBITDA, TOTAL_DEBT, …) are computed by the engine,
     * never seeded here; fields with no taxonomy match are reported as {@code unmappedFields},
     * not invented. The extraction row itself is left byte-identical.
     */
    @Transactional
    public SpreadAnalysis spreadFromExtraction(String reference, SpreadFromExtractionRequest req, String actor) {
        LoanApplication app = get(reference);
        Long extractionId = req == null ? null : req.extractionId();
        DocExtraction ext;
        if (extractionId != null) {
            ext = extractions.findById(extractionId)
                    .orElseThrow(() -> ApiException.notFound("No extraction: " + extractionId));
            if (!reference.equals(ext.getApplicationReference())) {
                throw ApiException.badRequest("Extraction #" + extractionId + " does not belong to " + reference);
            }
        } else {
            ext = extractions.findByApplicationReferenceAndStatusOrderByIdDesc(reference, "CONFIRMED").stream()
                    .findFirst().orElse(null);
        }
        if (ext == null) {
            throw ApiException.badRequest(
                    "No confirmed extraction to populate from — extract a document and confirm it first");
        }
        if (!"CONFIRMED".equals(ext.getStatus())) {
            throw ApiException.badRequest("Extraction #" + ext.getId() + " is " + ext.getStatus()
                    + " — only a CONFIRMED extraction may pre-fill the spread");
        }

        // Resolve the extraction field → canonical taxonomy MAPPING from the deal's FINANCIAL_TEMPLATE
        // master (so "extraction happens against the template master"). Template entries OVERRIDE /
        // AUGMENT the built-in default; when the template omits the map the built-in applies verbatim,
        // so behaviour is byte-identical for existing seeds.
        com.helix.origination.client.FinancialTemplateClient.FinancialTemplate tmpl =
                financialTemplates.resolve(app.getJurisdiction(), app.getSector(), app.getSegment());
        Map<String, String> mapping = new LinkedHashMap<>(EXTRACTION_TO_TAXONOMY);
        mapping.putAll(tmpl.extractionMap());   // keys already lower-cased by the client
        boolean templateMapped = !tmpl.extractionMap().isEmpty();

        Map<String, Object> exFields = ext.getFields() == null ? Map.of() : ext.getFields();
        Map<String, SpreadRequest.LineInput> lines = new LinkedHashMap<>();
        List<String> unmapped = new ArrayList<>();
        String periodLabel = req != null && req.periodLabel() != null && !req.periodLabel().isBlank()
                ? req.periodLabel() : null;
        for (var e : exFields.entrySet()) {
            // reporting_period seeds the DRAFT period label (it is not a figure line).
            if (periodLabel == null && "reporting_period".equalsIgnoreCase(e.getKey())) {
                Object pv = unwrap(e.getValue());
                if (pv != null && !String.valueOf(pv).isBlank()) {
                    periodLabel = String.valueOf(pv);
                }
                continue;
            }
            String canonical = mapping.get(e.getKey().toLowerCase(Locale.ROOT));
            Double value = extractNumber(e.getValue());
            if (canonical == null || value == null) {
                if (value != null || isFigureLike(e.getKey())) {
                    unmapped.add(e.getKey());   // a figure we can't safely place (e.g. derived EBITDA)
                }
                continue;
            }
            Double conf = extractConfidence(e.getValue());
            lines.put(canonical, new SpreadRequest.LineInput(
                    value, "extraction#" + ext.getId(), extractPage(e.getValue()), "ai-extraction",
                    conf == null ? ext.getOverallConfidence() : conf));
        }
        if (lines.isEmpty()) {
            throw ApiException.badRequest(
                    "The confirmed extraction has no figure fields that map to the spread taxonomy "
                            + "(populate from a financial-statement extraction)");
        }

        String label = periodLabel != null ? periodLabel : "AI-DRAFT";
        String gaap = req != null && req.gaap() != null && !req.gaap().isBlank() ? req.gaap() : "IND_AS";
        String ccy = req != null && req.currency() != null && !req.currency().isBlank()
                ? req.currency()
                : (app.getCurrency() == null || app.getCurrency().isBlank() ? "INR" : app.getCurrency());
        String note = req != null && req.note() != null ? req.note()
                : "Draft pre-filled from confirmed extraction #" + ext.getId();

        SpreadRequest.PeriodInput period = new SpreadRequest.PeriodInput(label, gaap, ccy, null, null, lines);
        SpreadRequest spreadReq = new SpreadRequest(List.of(period), null, "DOC_INTEL", note);

        // Reuse the authoritative spread builder: it persists the cells, records the version
        // (source DOC_INTEL) and — critically — leaves the deal UNCONFIRMED (spreadConfirmed=false).
        SpreadAnalysis result = spread(reference, spreadReq, actor);

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("extractionId", ext.getId());
        detail.put("mappedLines", lines.size());
        detail.put("unmappedFields", unmapped);
        detail.put("advisory", true);
        detail.put("financialTemplate", tmpl.templateKey());
        detail.put("mappingSource", templateMapped ? "TEMPLATE_MASTER" : "BUILT_IN");
        detail.put("spreadConfirmed", result.spreadConfirmed());   // false — human confirm still required
        audit.ai("financial-spreading", "SPREAD_DRAFTED_FROM_EXTRACTION", "Application", reference,
                "Pre-filled a DRAFT spread from confirmed extraction #%d (%d line(s) mapped) — analyst confirm still required"
                        .formatted(ext.getId(), lines.size()), detail);
        return result;
    }

    /** BUILT-IN default extraction field names -> canonical INPUT taxonomy keys — the fallback used
     *  when the resolved FINANCIAL_TEMPLATE master omits (or does not override) a mapping entry, so
     *  spreading stays byte-identical for existing seeds. The authoritative, governed mapping lives in
     *  the FINANCIAL_TEMPLATE master payload ({@code extractionMap}); see {@link #spreadFromExtraction}.
     *  Derived lines (EBITDA, TOTAL_DEBT, …) are intentionally absent — the engine computes them. */
    private static final Map<String, String> EXTRACTION_TO_TAXONOMY = extractionMap();

    private static Map<String, String> extractionMap() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("revenue", "REVENUE"); m.put("sales", "REVENUE"); m.put("turnover", "REVENUE");
        m.put("total_revenue", "REVENUE"); m.put("annual_turnover", "REVENUE");
        m.put("cogs", "COGS"); m.put("cost_of_goods_sold", "COGS"); m.put("cost_of_sales", "COGS");
        m.put("operating_expenses", "OPERATING_EXPENSES"); m.put("opex", "OPERATING_EXPENSES");
        m.put("depreciation", "DEPRECIATION"); m.put("depreciation_amortisation", "DEPRECIATION");
        m.put("interest_expense", "INTEREST_EXPENSE"); m.put("finance_cost", "INTEREST_EXPENSE");
        m.put("interest", "INTEREST_EXPENSE");
        m.put("tax", "TAX"); m.put("tax_expense", "TAX");
        m.put("total_assets", "TOTAL_ASSETS");
        m.put("current_assets", "CURRENT_ASSETS");
        m.put("cash", "CASH"); m.put("cash_equivalents", "CASH");
        m.put("current_liabilities", "CURRENT_LIABILITIES");
        m.put("short_term_debt", "SHORT_TERM_DEBT"); m.put("st_debt", "SHORT_TERM_DEBT");
        m.put("long_term_debt", "LONG_TERM_DEBT"); m.put("lt_debt", "LONG_TERM_DEBT");
        m.put("term_debt", "LONG_TERM_DEBT");
        // total_debt is a DERIVED line; seed it as long-term debt so the DRAFT is useful and the
        // analyst splits short/long in the grid before confirming.
        m.put("total_debt", "LONG_TERM_DEBT");
        m.put("current_portion_ltd", "CURRENT_PORTION_LTD");
        m.put("net_worth", "NET_WORTH"); m.put("networth", "NET_WORTH"); m.put("equity", "NET_WORTH");
        m.put("shareholders_equity", "NET_WORTH");
        m.put("cfo", "CFO"); m.put("cash_flow_operations", "CFO"); m.put("operating_cash_flow", "CFO");
        return m;
    }

    private static boolean isFigureLike(String key) {
        String k = key.toLowerCase(Locale.ROOT);
        return k.contains("revenue") || k.contains("ebitda") || k.contains("debt") || k.contains("asset")
                || k.contains("turnover") || k.contains("profit") || k.contains("income")
                || k.contains("liabilit") || k.contains("cash") || k.contains("worth");
    }

    /** Unwraps the {@code {value,confidence,sourcePage}} extraction cell (or a bare value). */
    private static Object unwrap(Object cell) {
        if (cell instanceof Map<?, ?> m && m.containsKey("value")) {
            return m.get("value");
        }
        return cell;
    }

    private static Double extractNumber(Object cell) {
        Object v = unwrap(cell);
        if (v instanceof Number n) {
            return n.doubleValue();
        }
        if (v instanceof String s) {
            String cleaned = s.replaceAll("[^0-9.\\-]", "");
            if (cleaned.isBlank() || "-".equals(cleaned) || ".".equals(cleaned)) {
                return null;
            }
            try {
                return Double.parseDouble(cleaned);
            } catch (NumberFormatException ex) {
                return null;
            }
        }
        return null;
    }

    private static Double extractConfidence(Object cell) {
        if (cell instanceof Map<?, ?> m && m.get("confidence") instanceof Number n) {
            return n.doubleValue();
        }
        return null;
    }

    private static String extractPage(Object cell) {
        if (cell instanceof Map<?, ?> m && m.get("sourcePage") != null) {
            return String.valueOf(m.get("sourcePage"));
        }
        return null;
    }

    // ------------------------------------------------- spread version timeline

    /**
     * Appends an immutable {@link SpreadVersion} carrying the freshly built analysis
     * snapshot. Pure history: the live spread tables, the confirm-gate and the rating
     * read path never read these rows, so the authoritative figure path is untouched.
     */
    private void recordSpreadVersion(LoanApplication app, String reference, SpreadRequest req,
                                     SpreadAnalysis result, String actor) {
        // Version history is STRICTLY auxiliary. Nothing here — an unrecognised source
        // marker, a snapshot serialisation error, or a persistence failure — may fail the
        // authoritative spread that already committed above. So the source is tolerant
        // (unknown falls back rather than throwing) and the whole body is non-fatal.
        try {
            int versionNo = spreadVersions.findTopByApplicationIdOrderByVersionNoDesc(app.getId())
                    .map(v -> v.getVersionNo() + 1).orElse(1);
            String source;
            if (req.source() != null && !req.source().isBlank()) {
                String s = req.source().trim().toUpperCase();
                source = SPREAD_VERSION_SOURCES.contains(s) ? s
                        : (versionNo == 1 ? "MANUAL" : "RESUBMISSION");
            } else {
                source = versionNo == 1 ? "MANUAL" : "RESUBMISSION";
            }

            // A resubmission supersedes any prior confirmation (spread() already reset the
            // gate to false) — clear stale confirmed stamps so the timeline stays truthful.
            if (versionNo > 1) {
                for (SpreadVersion prev : spreadVersions.findByApplicationIdOrderByVersionNoAsc(app.getId())) {
                    if (prev.isConfirmed()) {
                        prev.setConfirmed(false); prev.setConfirmedBy(null); prev.setConfirmedAt(null);
                        spreadVersions.save(prev);
                    }
                }
            }
            SpreadVersion version = new SpreadVersion();
            version.setApplicationId(app.getId());
            version.setVersionNo(versionNo);
            version.setCreatedBy(actor);
            version.setSource(source);
            version.setNote(req.note());
            version.setSnapshot(json.writeValueAsString(result));
            spreadVersions.save(version);

            audit.human(actor, "SPREAD_VERSION_RECORDED", "Application", reference,
                    "Spread version %d recorded (%s, %d period(s))".formatted(
                            versionNo, source, result.periods().size()),
                    Map.of("versionNo", versionNo, "source", source, "periods", result.periods().size()));
        } catch (Exception e) {
            // Never propagate: the spread is committed; the timeline is best-effort.
            log.warn("Spread version timeline write failed for {} (non-fatal): {}", reference, e.getMessage());
        }
    }

    @Transactional(readOnly = true)
    public List<SpreadVersionView> spreadVersionsFor(String reference) {
        LoanApplication app = get(reference);
        return spreadVersions.findByApplicationIdOrderByVersionNoAsc(app.getId()).stream()
                .map(v -> new SpreadVersionView(v.getVersionNo(), v.getCreatedBy(), v.getCreatedAt(),
                        v.getSource(), v.isConfirmed(), v.getConfirmedBy(), v.getConfirmedAt(), v.getNote()))
                .toList();
    }

    @Transactional(readOnly = true)
    public SpreadVersionDetail spreadVersionDetail(String reference, int versionNo) {
        LoanApplication app = get(reference);
        SpreadVersion v = spreadVersions.findByApplicationIdAndVersionNo(app.getId(), versionNo)
                .orElseThrow(() -> ApiException.notFound(
                        "No spread version %d for %s".formatted(versionNo, reference)));
        JsonNode snapshot;
        try {
            snapshot = json.readTree(v.getSnapshot() == null ? "null" : v.getSnapshot());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(
                    "Corrupt spread-version snapshot for %s v%d".formatted(reference, versionNo), e);
        }
        return new SpreadVersionDetail(v.getVersionNo(), v.getCreatedBy(), v.getCreatedAt(), v.getSource(),
                v.isConfirmed(), v.getConfirmedBy(), v.getConfirmedAt(), v.getNote(), snapshot);
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
        // Confirmation is a SINGLE authoritative pointer: clear it on every prior version,
        // then stamp only the latest — so the timeline never shows two "confirmed" versions
        // after a re-confirm. History only; the gate itself is the spreadConfirmed flag above.
        List<SpreadVersion> all = spreadVersions.findByApplicationIdOrderByVersionNoAsc(app.getId());
        SpreadVersion latest = null;
        for (SpreadVersion v : all) {
            if (v.isConfirmed()) { v.setConfirmed(false); v.setConfirmedBy(null); v.setConfirmedAt(null); }
            latest = v;
        }
        if (latest != null) {
            latest.setConfirmed(true);
            latest.setConfirmedBy(actor);
            latest.setConfirmedAt(Instant.now());
        }
        spreadVersions.saveAll(all);
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
        // Native values per period feed the (unit-free) ratios; presentation-normalised
        // values feed cross-period trends so currency switches don't corrupt growth.
        Map<Integer, Map<String, Double>> normalisedByOrdinal = new LinkedHashMap<>();
        String presentationCurrency = null;

        var tmpl = financialTemplates.resolve(app.getJurisdiction(), app.getSector(), app.getSegment());

        for (FinancialPeriod p : ps) {
            List<SpreadCell> periodCells = cells.findByPeriodId(p.getId());
            Map<String, Double> values = new LinkedHashMap<>();
            List<CellView> views = new ArrayList<>();
            // Order: canonical input, canonical derived, then any template extra input/derived lines.
            List<String> ordered = new ArrayList<>(CanonicalTaxonomy.INPUT_LINES);
            ordered.addAll(CanonicalTaxonomy.DERIVED_LINES);
            for (var l : tmpl.extraInputs()) ordered.add(l.key());
            for (var d : tmpl.extraDerived()) ordered.add(d.key());
            for (String key : ordered) {
                periodCells.stream().filter(c -> c.getTaxonomyKey().equals(key)).findFirst()
                        .ifPresent(c -> {
                            values.put(c.getTaxonomyKey(), c.getValue());
                            views.add(toView(c));
                        });
            }
            // Ratios: the standard set (unchanged) MERGED with the template's extra ratios
            // (formula-driven over this period's values). Standard keys are never overwritten.
            Map<String, Double> ratios = Ratios.compute(values);
            for (var r : tmpl.extraRatios()) {
                if (r.key() == null || r.formula() == null || ratios.containsKey(r.key())) continue;
                ratios.put(r.key(), com.helix.common.formula.FormulaEvaluator.evalRounded(r.formula(), values));
            }
            double rate = p.getFxToPresentation() == null ? 1.0 : p.getFxToPresentation();
            Map<String, Double> presentationValues = normalise(values, rate);
            normalisedByOrdinal.put(p.getOrdinal(), presentationValues);
            if (p.getPresentationCurrency() != null) presentationCurrency = p.getPresentationCurrency();
            periodViews.add(new PeriodAnalysis(p.getId(), p.getLabel(), p.getGaap(), p.getCurrency(),
                    views, ratios,
                    p.getPresentationCurrency(), p.getFxToPresentation(), presentationValues,
                    p.getPeriodEnd(), p.getFxRateSource()));
        }

        // Trends on presentation-normalised values (the currency-consistency fix).
        Map<String, Double> trends = trends(normalisedByOrdinal);
        // Benchmark flags read ratios (unit-free), so native vs normalised is irrelevant.
        List<String> flags = benchmarkFlags(normalisedByOrdinal.get(0));
        boolean currencyConsistent = ps.stream()
                .map(FinancialPeriod::getCurrency).filter(java.util.Objects::nonNull)
                .distinct().count() <= 1;
        return new SpreadAnalysis(reference, app.isSpreadConfirmed(), periodViews, trends, flags,
                presentationCurrency, currencyConsistent, tmpl.templateKey());
    }

    @Transactional(readOnly = true)
    public CreditInputs creditInputs(String reference) {
        LoanApplication app = get(reference);
        List<FinancialPeriod> ps = periods.findByApplicationIdOrderByOrdinalAsc(app.getId());
        Map<String, Double> latest = new LinkedHashMap<>();
        // Normalised per period so cross-period trends are currency-consistent.
        Map<Integer, Map<String, Double>> normalisedByOrdinal = new LinkedHashMap<>();
        for (FinancialPeriod p : ps) {
            Map<String, Double> values = new LinkedHashMap<>();
            for (SpreadCell c : cells.findByPeriodId(p.getId())) {
                values.put(c.getTaxonomyKey(), c.getValue());
            }
            double rate = p.getFxToPresentation() == null ? 1.0 : p.getFxToPresentation();
            normalisedByOrdinal.put(p.getOrdinal(), normalise(values, rate));
            if (p.getOrdinal() == 0) {
                latest = values;   // latest stays in its native currency for the authoritative path
            }
        }
        // Standard ratios + the resolved template's extra ratios, so downstream consumers
        // (e.g. the scoring-model engine's RATIO:* parameter source) can reference them too.
        Map<String, Double> ratios = Ratios.compute(latest);
        var tmpl = financialTemplates.resolve(app.getJurisdiction(), app.getSector(), app.getSegment());
        for (var r : tmpl.extraRatios()) {
            if (r.key() == null || r.formula() == null || ratios.containsKey(r.key())) continue;
            ratios.put(r.key(), com.helix.common.formula.FormulaEvaluator.evalRounded(r.formula(), latest));
        }
        // Additive: the full facility list + collateral cover (the deal envelope's building blocks)
        // so risk-service can capitalise & price PER FACILITY. Ordered by facility ordinal so index 0
        // is the primary; every existing consumer that reads only the scalar fields is unaffected.
        List<CreditFacility> facilityInputs = facilities.findByApplicationIdOrderByOrdinalAsc(app.getId())
                .stream()
                .map(f -> new CreditFacility(f.getId(), f.getReference(), f.getFacilityType(),
                        f.getAmount(), f.getCurrency(), f.getTenorMonths(), f.isPrimary()))
                .toList();
        List<CreditCollateral> collateralInputs = collaterals.findByApplicationId(app.getId())
                .stream()
                .map(c -> new CreditCollateral(c.getFacilityId(), c.getCollateralType(), c.getMarketValue()))
                .toList();

        return new CreditInputs(app.getReference(), app.getCounterpartyId(), app.getCounterpartyRef(),
                app.getCounterpartyName(), app.getJurisdiction(), app.getSegment(), app.getSector(),
                app.getFacilityType(),
                app.getRequestedAmount(), app.getCurrency(), app.getTenorMonths(), app.getCollateralType(),
                app.getCollateralValue(), app.isSecured(), app.isSpreadConfirmed(),
                latest, ratios, trends(normalisedByOrdinal),
                facilityInputs, collateralInputs);
    }

    private static java.time.LocalDate parsePeriodEnd(String iso) {
        if (iso == null || iso.isBlank()) return null;
        try { return java.time.LocalDate.parse(iso.trim()); } catch (Exception e) { return null; }
    }

    /** Restate every monetary line into the presentation currency (value * fxToPresentation). */
    private Map<String, Double> normalise(Map<String, Double> values, double fxToPresentation) {
        if (fxToPresentation == 1.0) return values;
        Map<String, Double> out = new LinkedHashMap<>();
        for (var e : values.entrySet()) {
            out.put(e.getKey(), Math.round(e.getValue() * fxToPresentation * 100.0) / 100.0);
        }
        return out;
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

    /**
     * Guards a pre-sanction structure edit: throws 409 CONFLICT unless the owning application is
     * still in the structuring phase (see {@link #STRUCTURE_EDITABLE_STATUSES}). Post-sanction the
     * facility/collateral/sublimit of record is frozen and changes must go through the amendment path.
     */
    private LoanApplication assertStructureEditable(LoanApplication app) {
        if (!STRUCTURE_EDITABLE_STATUSES.contains(app.getStatus())) {
            throw ApiException.conflict(
                    ("Deal %s is %s — the facility / collateral / sublimit structure is frozen after "
                     + "the structuring phase; a post-sanction change must go through the approved "
                     + "amendment path (applyAmendment), not a direct edit")
                            .formatted(app.getReference(), app.getStatus()));
        }
        return app;
    }

    private LoanApplication assertStructureEditable(Long applicationId) {
        LoanApplication app = applications.findById(applicationId)
                .orElseThrow(() -> ApiException.notFound("No application: " + applicationId));
        return assertStructureEditable(app);
    }

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

    /**
     * Sets the facility's rate type. FIXED carries no benchmark; FLOATING needs
     * benchmark code + spread + reset frequency. The actual rate at each reset is
     * resolved at schedule-time against the BENCHMARK master, so a benchmark move
     * (EBLR/SOFR update) flows through to the next period automatically.
     */
    @Transactional
    public ProposedFacility setRateType(String reference, String facilityRef, String rateType,
                                        String benchmarkCode, Double spreadBps,
                                        Integer resetFrequencyMonths, String actor) {
        ProposedFacility f = facilities.findByApplicationIdOrderByOrdinalAsc(get(reference).getId()).stream()
                .filter(x -> facilityRef.equals(x.getReference())).findFirst()
                .orElseThrow(() -> ApiException.notFound("No facility " + facilityRef + " on " + reference));
        String rt = rateType == null ? "FIXED" : rateType.toUpperCase();
        if (!"FIXED".equals(rt) && !"FLOATING".equals(rt)) {
            throw ApiException.badRequest("rateType must be FIXED or FLOATING");
        }
        if ("FLOATING".equals(rt)) {
            if (benchmarkCode == null || benchmarkCode.isBlank()) {
                throw ApiException.badRequest("FLOATING requires benchmarkCode");
            }
            if (spreadBps == null) {
                throw ApiException.badRequest("FLOATING requires spreadBps");
            }
        }
        f.setRateType(rt);
        f.setBenchmarkCode("FLOATING".equals(rt) ? benchmarkCode.toUpperCase() : null);
        f.setSpreadBps("FLOATING".equals(rt) ? spreadBps : null);
        f.setResetFrequencyMonths("FLOATING".equals(rt)
                ? (resetFrequencyMonths == null ? 3 : resetFrequencyMonths) : null);
        ProposedFacility saved = facilities.save(f);
        audit.human(actor, "FACILITY_RATE_TYPE_SET", "ProposedFacility", String.valueOf(f.getId()),
                "Rate type %s on %s%s".formatted(rt, facilityRef,
                        "FLOATING".equals(rt)
                                ? " — " + benchmarkCode + " + " + spreadBps + "bps, reset " + saved.getResetFrequencyMonths() + "m"
                                : ""),
                Map.of("facilityRef", facilityRef, "rateType", rt,
                        "benchmarkCode", saved.getBenchmarkCode() == null ? "" : saved.getBenchmarkCode(),
                        "spreadBps", saved.getSpreadBps() == null ? 0.0 : saved.getSpreadBps()));
        return saved;
    }

    @Transactional
    public void removeFacility(Long facilityId, String actor) {
        ProposedFacility f = facilities.findById(facilityId)
                .orElseThrow(() -> ApiException.notFound("No facility: " + facilityId));
        assertStructureEditable(f.getApplicationId());
        if (f.isPrimary()) {
            throw ApiException.badRequest("Cannot remove the primary facility");
        }
        facilities.delete(f);
        audit.human(actor, "FACILITY_REMOVED", "ProposedFacility", String.valueOf(facilityId),
                "Removed " + f.getFacilityType(), Map.of("facilityType", f.getFacilityType()));
    }

    /**
     * Edit a proposed facility in place (pre-sanction only). Partial update — only non-null fields
     * are applied. The auto-created PRIMARY facility is not editable here (it mirrors the
     * application's headline request); post-sanction changes route through {@link #applyAmendment}.
     * A downward amount change may not fall below what the sublimits already allocate (counting each
     * interchangeable group once at its shared cap), so the structuring cap invariant is preserved.
     */
    @Transactional
    public ProposedFacility updateFacility(Long facilityId, UpdateFacilityRequest req, String actor) {
        ProposedFacility f = facilities.findById(facilityId)
                .orElseThrow(() -> ApiException.notFound("No facility: " + facilityId));
        assertStructureEditable(f.getApplicationId());
        if (f.isPrimary()) {
            throw ApiException.badRequest(
                    "Cannot edit the primary facility — it mirrors the application's headline request");
        }
        String oldType = f.getFacilityType();
        double oldAmount = f.getAmount();
        if (req.facilityType() != null && !req.facilityType().isBlank()) f.setFacilityType(req.facilityType());
        if (req.currency() != null && !req.currency().isBlank()) f.setCurrency(req.currency());
        if (req.tenorMonths() != null) f.setTenorMonths(req.tenorMonths());
        if (req.purpose() != null) f.setPurpose(req.purpose());
        if (req.indicativeRate() != null) f.setIndicativeRate(req.indicativeRate());
        if (req.amount() != null) {
            double allocated = effectiveSublimitAllocation(sublimits.findByFacilityIdOrderByOrdinalAsc(facilityId));
            if (req.amount() + 1e-6 < allocated) {
                throw ApiException.badRequest(
                        ("New facility amount %.0f is below the %.0f already allocated to its sublimits "
                         + "(interchangeable groups counted once at their shared cap) — reduce the sublimits first")
                                .formatted(req.amount(), allocated));
            }
            f.setAmount(req.amount());
        }
        ProposedFacility saved = facilities.save(f);
        audit.human(actor, "FACILITY_UPDATED", "ProposedFacility", String.valueOf(facilityId),
                "Updated %s: type %s -> %s, amount %.0f -> %.0f".formatted(saved.getReference(),
                        oldType, saved.getFacilityType(), oldAmount, saved.getAmount()),
                Map.of("facilityType", saved.getFacilityType(), "amount", saved.getAmount(),
                        "oldAmount", oldAmount));
        return saved;
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

    /**
     * Edit a collateral's DESCRIPTIVE fields in place (pre-sanction only). Partial update — only
     * non-null fields are applied. CRITICAL GOVERNANCE: {@code marketValue} is NOT editable here
     * (the request record has no such field), so a valuation can never be silently overwritten from
     * this path — a market-value change must route through the collateral-intel revalue → human
     * apply gate ({@code CollateralIntelligenceService.revalue/review}), which is where the LTV
     * assessment and accountability audit are stamped.
     */
    @Transactional
    public Collateral updateCollateral(Long collateralId, UpdateCollateralRequest req, String actor) {
        Collateral c = collaterals.findById(collateralId)
                .orElseThrow(() -> ApiException.notFound("No collateral: " + collateralId));
        assertStructureEditable(c.getApplicationId());
        if (req.collateralType() != null && !req.collateralType().isBlank()) {
            c.setCollateralType(req.collateralType().toUpperCase());
        }
        if (req.description() != null && !req.description().isBlank()) c.setDescription(req.description());
        if (req.valuationDate() != null && !req.valuationDate().isBlank()) {
            c.setValuationDate(LocalDate.parse(req.valuationDate()));
        }
        if (req.valuationSource() != null) c.setValuationSource(req.valuationSource());
        if (req.haircut() != null) {
            if (req.haircut() < 0 || req.haircut() > 1) {
                throw ApiException.badRequest("haircut must be a fraction between 0 and 1");
            }
            c.setHaircut(req.haircut());
        }
        if (req.owner() != null) c.setOwner(req.owner());
        if (req.location() != null) c.setLocation(req.location());
        if (req.perfectionStatus() != null && !req.perfectionStatus().isBlank()) {
            c.setPerfectionStatus(req.perfectionStatus());
        }
        if (req.facilityId() != null) c.setFacilityId(req.facilityId() <= 0 ? null : req.facilityId());
        Collateral saved = collaterals.save(c);
        audit.human(actor, "COLLATERAL_UPDATED", "Collateral", String.valueOf(collateralId),
                "Updated %s collateral (haircut %.0f%%, %s) — market value unchanged (route revaluation via collateral-intel)"
                        .formatted(saved.getCollateralType(), saved.getHaircut() * 100, saved.getPerfectionStatus()),
                Map.of("type", saved.getCollateralType(), "haircut", saved.getHaircut(),
                        "marketValue", saved.getMarketValue()));
        return saved;
    }

    /** Delete a collateral (pre-sanction only) — parity with facility / sublimit delete. */
    @Transactional
    public void removeCollateral(Long collateralId, String actor) {
        Collateral c = collaterals.findById(collateralId)
                .orElseThrow(() -> ApiException.notFound("No collateral: " + collateralId));
        assertStructureEditable(c.getApplicationId());
        collaterals.delete(c);
        audit.human(actor, "COLLATERAL_REMOVED", "Collateral", String.valueOf(collateralId),
                "Removed %s collateral".formatted(c.getCollateralType()),
                Map.of("type", c.getCollateralType(), "marketValue", c.getMarketValue()));
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
     * Adds a sublimit to a facility. Enforces that the sublimits cannot exceed the parent facility
     * amount — protects the structuring cap. The cap is computed with each interchangeable
     * (fungible) group counted ONCE at its shared cap (see {@link #effectiveSublimitAllocation});
     * members of a fungible pool share one cap, so summing them would double-count the same headroom.
     */
    @Transactional
    public Sublimit addSublimit(Long facilityId, AddSublimitRequest req, String actor) {
        ProposedFacility f = facilities.findById(facilityId)
                .orElseThrow(() -> ApiException.notFound("No facility: " + facilityId));
        // NB: ADD stays permissive at any status (legacy behaviour — the lifecycle can still bolt on a
        // sublimit post-sanction). Only in-place EDITS and DELETES are pre-sanction-gated below.
        if (req.currency() != null && !req.currency().equalsIgnoreCase(f.getCurrency())) {
            // We could support multi-currency sublimits in future; flag rather than allow silent drift.
            throw ApiException.badRequest("Sublimit currency must match the parent facility (" + f.getCurrency() + ")");
        }
        List<Sublimit> existing = sublimits.findByFacilityIdOrderByOrdinalAsc(facilityId);
        Sublimit s = new Sublimit();
        s.setFacilityId(facilityId);
        s.setOrdinal(existing.size());
        s.setCode(req.code().toUpperCase());
        s.setProductType(req.productType().toUpperCase());
        s.setAmount(req.amount());
        s.setCurrency(req.currency());
        s.setTenorMonths(req.tenorMonths());
        s.setPurpose(req.purpose());
        s.setInterchangeableGroup(normaliseGroup(req.interchangeableGroup()));
        // Cap check on (existing + candidate) with fungible groups counted once at their shared cap.
        List<Sublimit> combined = new ArrayList<>(existing);
        combined.add(s);
        double allocated = effectiveSublimitAllocation(combined);
        if (allocated > f.getAmount() + 1e-6) {
            throw ApiException.badRequest(
                    ("Sublimit would breach facility cap: effective allocation %.0f > facility cap %.0f "
                     + "(interchangeable groups counted once at their shared cap, not summed)")
                            .formatted(allocated, f.getAmount()));
        }
        Sublimit saved = sublimits.save(s);
        audit.human(actor, "SUBLIMIT_ADDED", "ProposedFacility", String.valueOf(facilityId),
                "Added %s sublimit %.0f%s".formatted(s.getCode(), s.getAmount(),
                        s.isFungible() ? " (group " + s.getInterchangeableGroup() + ")" : ""),
                Map.of("code", s.getCode(), "amount", s.getAmount(),
                        "interchangeableGroup", String.valueOf(s.getInterchangeableGroup())));
        return saved;
    }

    /** Normalise an interchangeable-group key: blank → null (hard cap), else trimmed upper-case. */
    private static String normaliseGroup(String g) {
        return g == null || g.isBlank() ? null : g.trim().toUpperCase(Locale.ROOT);
    }

    /**
     * Effective allocation of a facility's cap by its sublimits, counting each interchangeable
     * (fungible) group ONCE at its shared cap — NOT summing the members. Members of a fungible group
     * share a single pool (utilisation moves freely within it), so summing their {@code amount}s
     * double-counts the same headroom. The model carries no explicit group-cap field, so the shared
     * cap is taken as the MAX member amount (members of one pool are set to the pool size). Hard
     * (non-fungible) sublimits are summed individually. This is the ORIGINATION cap/display view; the
     * limit-service UtilisationService owns the authoritative runtime pooled-headroom enforcement.
     */
    private double effectiveSublimitAllocation(List<Sublimit> subs) {
        double hard = subs.stream().filter(s -> !s.isFungible())
                .mapToDouble(Sublimit::getAmount).sum();
        Map<String, Double> groupCap = new LinkedHashMap<>();
        for (Sublimit s : subs) {
            if (s.isFungible()) {
                groupCap.merge(s.getInterchangeableGroup(), s.getAmount(), Math::max);
            }
        }
        double pooled = groupCap.values().stream().mapToDouble(Double::doubleValue).sum();
        return hard + pooled;
    }

    @Transactional(readOnly = true)
    public List<Sublimit> sublimitsFor(Long facilityId) {
        return sublimits.findByFacilityIdOrderByOrdinalAsc(facilityId);
    }

    @Transactional
    public void removeSublimit(Long id, String actor) {
        Sublimit s = sublimits.findById(id)
                .orElseThrow(() -> ApiException.notFound("No sublimit: " + id));
        ProposedFacility f = facilities.findById(s.getFacilityId())
                .orElseThrow(() -> ApiException.notFound("No facility for sublimit " + id));
        assertStructureEditable(f.getApplicationId());
        sublimits.delete(s);
        audit.human(actor, "SUBLIMIT_REMOVED", "Sublimit", String.valueOf(id),
                "Removed " + s.getCode(), Map.of());
    }

    /**
     * Edit a sublimit in place (pre-sanction only). Partial update — only non-null fields are applied.
     * After the edit the parent facility cap is re-checked with each interchangeable group counted
     * ONCE at its shared cap (fungible members are not summed). Pass a blank {@code interchangeableGroup}
     * to convert a fungible sublimit back to a hard-cap sublimit.
     */
    @Transactional
    public Sublimit updateSublimit(Long id, UpdateSublimitRequest req, String actor) {
        Sublimit s = sublimits.findById(id)
                .orElseThrow(() -> ApiException.notFound("No sublimit: " + id));
        ProposedFacility f = facilities.findById(s.getFacilityId())
                .orElseThrow(() -> ApiException.notFound("No facility for sublimit " + id));
        assertStructureEditable(f.getApplicationId());
        if (req.code() != null && !req.code().isBlank()) s.setCode(req.code().toUpperCase());
        if (req.productType() != null && !req.productType().isBlank()) s.setProductType(req.productType().toUpperCase());
        if (req.amount() != null) s.setAmount(req.amount());
        if (req.currency() != null && !req.currency().isBlank()) s.setCurrency(req.currency());
        if (req.tenorMonths() != null) s.setTenorMonths(req.tenorMonths());
        if (req.purpose() != null) s.setPurpose(req.purpose());
        // interchangeableGroup is a tri-state edit: null = leave as-is, blank = clear to hard cap,
        // else set the pool key. Only touch it when the caller actually supplied the field.
        if (req.interchangeableGroup() != null) s.setInterchangeableGroup(normaliseGroup(req.interchangeableGroup()));
        if (s.getCurrency() != null && !s.getCurrency().equalsIgnoreCase(f.getCurrency())) {
            throw ApiException.badRequest("Sublimit currency must match the parent facility (" + f.getCurrency() + ")");
        }
        // Cap check on (siblings + this edited sublimit) with fungible groups counted once.
        List<Sublimit> combined = sublimits.findByFacilityIdOrderByOrdinalAsc(f.getId()).stream()
                .filter(x -> !x.getId().equals(s.getId()))
                .collect(Collectors.toCollection(ArrayList::new));
        combined.add(s);
        double allocated = effectiveSublimitAllocation(combined);
        if (allocated > f.getAmount() + 1e-6) {
            throw ApiException.badRequest(
                    ("Sublimit edit would breach facility cap: effective allocation %.0f > facility cap %.0f "
                     + "(interchangeable groups counted once at their shared cap, not summed)")
                            .formatted(allocated, f.getAmount()));
        }
        Sublimit saved = sublimits.save(s);
        audit.human(actor, "SUBLIMIT_UPDATED", "Sublimit", String.valueOf(id),
                "Updated %s sublimit %.0f%s".formatted(saved.getCode(), saved.getAmount(),
                        saved.isFungible() ? " (group " + saved.getInterchangeableGroup() + ")" : " (hard cap)"),
                Map.of("code", saved.getCode(), "amount", saved.getAmount(),
                        "interchangeableGroup", String.valueOf(saved.getInterchangeableGroup())));
        return saved;
    }

    /** Maps a facility entity to its view, embedding sublimits and interchangeability groups. */
    private FacilityView toFacilityView(ProposedFacility f) {
        List<Sublimit> ss = sublimits.findByFacilityIdOrderByOrdinalAsc(f.getId());
        List<SublimitView> subViews = ss.stream().map(s -> new SublimitView(
                s.getId(), s.getFacilityId(), s.getOrdinal(), s.getCode(), s.getProductType(),
                s.getAmount(), s.getCurrency(), s.getTenorMonths(), s.getPurpose(),
                s.getInterchangeableGroup(), s.isFungible())).toList();

        // Group fungible sublimits by group key. Members of an interchangeable group SHARE one cap
        // (utilisation moves freely within the pool), so the group's combined cap is its shared cap —
        // the MAX member amount — NOT the sum of member amounts. Summing would double-count the same
        // shared headroom (the bug this fixes). The model carries no explicit group-cap field; members
        // of a pool are set to the pool size, so max() is the shared cap.
        Map<String, List<Sublimit>> byGroup = ss.stream()
                .filter(Sublimit::isFungible)
                .collect(Collectors.groupingBy(Sublimit::getInterchangeableGroup, java.util.LinkedHashMap::new,
                        Collectors.toList()));
        List<InterchangeabilityGroupView> groupViews = byGroup.entrySet().stream()
                .map(e -> new InterchangeabilityGroupView(
                        e.getKey(),
                        e.getValue().stream().mapToDouble(Sublimit::getAmount).max().orElse(0.0),
                        f.getCurrency(),
                        e.getValue().stream().map(Sublimit::getCode).toList(),
                        e.getValue().size()))
                .toList();

        // Effective allocation counts each interchangeable group once at its shared cap (not summed),
        // consistent with the addSublimit / updateFacility cap checks, so headroom is truthful.
        double sublimitTotal = effectiveSublimitAllocation(ss);
        double headroom = Math.max(0.0, f.getAmount() - sublimitTotal);
        return new FacilityView(f.getId(), f.getReference(), f.getOrdinal(), f.isPrimary(),
                f.getFacilityType(), f.getAmount(), f.getCurrency(), f.getTenorMonths(),
                f.getPurpose(), f.getIndicativeRate(),
                f.getRateType(), f.getBenchmarkCode(), f.getSpreadBps(), f.getResetFrequencyMonths(),
                subViews, groupViews, sublimitTotal, headroom);
    }

    // ---------------------------------------------------- advisory LLM hooks

    /**
     * Advisory document-classification. Label capability: the reply is validated STRICTLY against
     * the {@link DocumentType} set (see {@link #parseDocumentType}); anything else falls back to the
     * keyword classifier. The prompt lists the allowed labels and is prose (never a JSON object).
     */
    private LlmResult llmClassify(String fileName, String declaredType, DocumentType deterministic) {
        String system = "You are an ADVISORY document classifier for wholesale-credit intake. Classify the "
                + "described document into EXACTLY ONE of these allowed types: " + allowedDocTypeLabels() + ". "
                + "This is an advisory suggestion for a human reviewer and never sets a figure, score or "
                + "decision. Reply with ONLY the single type label from the allowed list, nothing else. "
                + "capability=doc-classify";
        String user = "File name: " + nz(fileName)
                + (declaredType == null || declaredType.isBlank() ? "" : "\nDeclared type hint: " + declaredType)
                + "\nDeterministic keyword guess: " + deterministic.name();
        return safeComplete(LlmRequest.of("doc-classify", system, user));
    }

    private static String allowedDocTypeLabels() {
        return java.util.Arrays.stream(DocumentType.values()).map(Enum::name).collect(Collectors.joining(", "));
    }

    /** Strictly validate a model reply against the {@link DocumentType} set (trim/uppercase-normalise). */
    private static DocumentType parseDocumentType(String reply) {
        if (reply == null) {
            return null;
        }
        String t = reply.strip()
                .replaceAll("^[`'\"\\s]+", "")
                .replaceAll("[`'\".\\s]+$", "")
                .toUpperCase(Locale.ROOT);
        for (DocumentType dt : DocumentType.values()) {
            if (dt.name().equals(t)) {
                return dt;
            }
        }
        // Tolerate a label wrapped in a short phrase ("Type: FACILITY_DOC") — accept only when
        // exactly one allowed label appears as a whole token; ambiguous / none → deterministic fallback.
        Set<DocumentType> found = new java.util.LinkedHashSet<>();
        for (String tok : t.split("[^A-Z_]+")) {
            for (DocumentType dt : DocumentType.values()) {
                if (dt.name().equals(tok)) {
                    found.add(dt);
                }
            }
        }
        return found.size() == 1 ? found.iterator().next() : null;
    }

    /**
     * Advisory pre-confirm suggestion of candidate line items, grounded in the analyst-supplied
     * source lines. Extraction capability: the prompt asks for a JSON object. The reply is only ever
     * parsed to count candidate lines for the audit trail — never written into the spread.
     */
    private LlmResult llmSpreadingSuggest(String reference, SpreadRequest req) {
        SpreadRequest.PeriodInput latest = req.periods().get(0);
        StringBuilder lines = new StringBuilder();
        for (var e : latest.lines().entrySet()) {
            lines.append(e.getKey()).append('=').append(e.getValue().value()).append('\n');
        }
        String system = "You are an ADVISORY financial-spreading assistant. From the described source "
                + "financial lines, suggest the candidate canonical line items and their values as a flat "
                + "JSON object of lineKey -> value. This is a SUGGESTION only: the analyst-entered spread and "
                + "every deterministic ratio remain authoritative and are never replaced by your output. "
                + "Reuse the supplied figures verbatim and never invent values. Return ONLY a JSON object. "
                + "capability=spreading-extract";
        String user = "Application: " + reference + "\nPeriod: " + nz(latest.label())
                + "\nCurrency: " + nz(latest.currency()) + "\nSource lines:\n" + lines;
        return safeComplete(LlmRequest.of("spreading-extract", system, user));
    }

    private LlmResult safeComplete(LlmRequest req) {
        try {
            LlmResult r = llm.complete(req);
            return r == null ? LlmResult.notConfigured() : r;
        } catch (Exception e) {
            return LlmResult.failed(e.getMessage());
        }
    }

    /** Lenient JSON-object parse: strips code fences / prose wrapping; returns null on any failure. */
    private Map<String, Object> parseJsonObject(String text) {
        if (text == null) {
            return null;
        }
        String t = text.strip();
        if (t.startsWith("```")) {
            int nl = t.indexOf('\n');
            if (nl > 0) {
                t = t.substring(nl + 1);
            }
            if (t.endsWith("```")) {
                t = t.substring(0, t.length() - 3);
            }
            t = t.strip();
        }
        if (!t.startsWith("{")) {
            return null;
        }
        try {
            return json.readValue(t, new TypeReference<LinkedHashMap<String, Object>>() { });
        } catch (Exception ex) {
            return null;
        }
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
