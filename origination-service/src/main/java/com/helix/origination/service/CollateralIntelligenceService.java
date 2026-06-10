package com.helix.origination.service;

import com.helix.common.audit.AuditService;
import com.helix.common.web.ApiException;
import com.helix.origination.dto.CollateralIntelDtos.ConfirmCollateralExtractionRequest;
import com.helix.origination.dto.CollateralIntelDtos.RevalueRequest;
import com.helix.origination.dto.Dtos.AddCollateralRequest;
import com.helix.origination.entity.Collateral;
import com.helix.origination.entity.CollateralExtraction;
import com.helix.origination.entity.CollateralRevaluation;
import com.helix.origination.entity.LoanApplication;
import com.helix.origination.entity.ProposedFacility;
import com.helix.origination.repo.CollateralExtractionRepository;
import com.helix.origination.repo.CollateralRepository;
import com.helix.origination.repo.CollateralRevaluationRepository;
import com.helix.origination.repo.LoanApplicationRepository;
import com.helix.origination.repo.ProposedFacilityRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AI-assisted collateral intelligence (PRD §5, "extracting collateral details" +
 * "populating collateral fields automatically" + "LTV alerts with feedback
 * loop"). All advisory:
 *
 * <ol>
 *   <li><b>Type-aware extraction</b> over an uploaded collateral document
 *       (PROPERTY / VEHICLE / INSURANCE / TITLE_DEED / BOND / GUARANTOR);
 *       per-template field map + missing-mandatory list. Human confirms,
 *       which creates a real {@link Collateral} via the same service path
 *       analysts use manually.</li>
 *   <li><b>Revaluation with LTV alerts</b> — record a new market value and
 *       the observed drawn exposure; an LTV breach raises an advisory
 *       alert. The live collateral row is only updated when a human
 *       accepts the revaluation.</li>
 *   <li><b>Charge-Excel</b> generation — CSV (Excel-compatible) of every
 *       collateral's registration / valuation / perfection data for the
 *       limit-release file.</li>
 * </ol>
 *
 * Authoritative figures (capital projection, ECL) are never mutated here;
 * the confirm gate is what records analyst accountability.
 */
@Service
public class CollateralIntelligenceService {

    private static final double DEFAULT_LTV_THRESHOLD = 0.80;

    // ------ per-type mandatory-field templates (PRD §5 "mandatory vs optional") ------
    private static final Map<String, List<String>> MANDATORY = Map.of(
            "VALUATION_REPORT", List.of("marketValue", "valuationDate", "valuerName", "addressLine"),
            "TITLE_DEED",       List.of("registeredOwner", "addressLine", "propertyType", "registrationNo"),
            "INSURANCE_POLICY", List.of("policyNo", "insurerName", "sumInsured", "validUntil", "beneficiary"),
            "VEHICLE_RC",       List.of("identificationNo", "make", "model", "yearOfManufacture", "registeredOwner"),
            "BOND_CERT",        List.of("isin", "issuerName", "faceValue", "maturityDate"),
            "PG_DEED",          List.of("guarantorName", "guaranteeAmount", "guaranteeType"));

    private static final Map<String, String> KIND_TO_COLLATERAL_TYPE = Map.of(
            "VALUATION_REPORT", "PROPERTY",
            "TITLE_DEED", "PROPERTY",
            "INSURANCE_POLICY", "INSURANCE",
            "VEHICLE_RC", "VEHICLE",
            "BOND_CERT", "BONDS",
            "PG_DEED", "GUARANTOR");

    private static final Pattern MONEY = Pattern.compile(
            "(?:(?:aed|inr|usd|eur|gbp|sar|qar|kwd|omr|bhd)\\s*)?([0-9]{1,3}(?:[ ,][0-9]{3})+(?:\\.[0-9]+)?|[0-9]+(?:\\.[0-9]+)?)(?:\\s*(crore|cr|lakh|lac|million|mn|m|bn|billion))?",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern DATE_ISO = Pattern.compile("(\\d{4})-(\\d{1,2})-(\\d{1,2})");
    private static final Pattern DATE_DMY = Pattern.compile("(\\d{1,2})[/\\- ](\\d{1,2})[/\\- ](\\d{4})");
    private static final Pattern YEAR = Pattern.compile("\\b(19|20)\\d{2}\\b");

    private final CollateralExtractionRepository extractions;
    private final CollateralRevaluationRepository revaluations;
    private final CollateralRepository collaterals;
    private final ProposedFacilityRepository facilities;
    private final LoanApplicationRepository applications;
    private final OriginationService origination;
    private final AuditService audit;
    private final com.helix.common.governance.AiGovernanceClient governance;

    public CollateralIntelligenceService(CollateralExtractionRepository extractions,
                                         CollateralRevaluationRepository revaluations,
                                         CollateralRepository collaterals,
                                         ProposedFacilityRepository facilities,
                                         LoanApplicationRepository applications,
                                         OriginationService origination,
                                         AuditService audit,
                                         com.helix.common.governance.AiGovernanceClient governance) {
        this.extractions = extractions;
        this.revaluations = revaluations;
        this.collaterals = collaterals;
        this.facilities = facilities;
        this.applications = applications;
        this.origination = origination;
        this.audit = audit;
        this.governance = governance;
    }

    // ================================================================ 1. extraction

    @Transactional
    public CollateralExtraction extract(String reference, String documentKind, String text, String actor) {
        LoanApplication app = applications.findByReference(reference)
                .orElseThrow(() -> ApiException.notFound("No application: " + reference));
        governance.enforce(com.helix.common.governance.AiCapability.COLLATERAL_INTEL, app.getJurisdiction());
        String kind = documentKind.toUpperCase();
        if (!MANDATORY.containsKey(kind)) {
            throw ApiException.badRequest("Unknown documentKind '" + documentKind + "'");
        }
        Map<String, Object> fields = new LinkedHashMap<>();
        List<String> signals = new ArrayList<>();
        switch (kind) {
            case "VALUATION_REPORT" -> parseValuation(text, fields, signals);
            case "TITLE_DEED"       -> parseTitleDeed(text, fields, signals);
            case "INSURANCE_POLICY" -> parseInsurance(text, fields, signals);
            case "VEHICLE_RC"       -> parseVehicleRc(text, fields, signals);
            case "BOND_CERT"        -> parseBondCert(text, fields, signals);
            case "PG_DEED"          -> parsePgDeed(text, fields, signals);
            default                 -> { /* unreachable */ }
        }

        List<String> missing = new ArrayList<>();
        for (String required : MANDATORY.get(kind)) {
            if (!fields.containsKey(required)) missing.add(required);
        }
        double overall = fields.isEmpty() ? 0.0
                : Math.max(0.3, 1.0 - (double) missing.size() / MANDATORY.get(kind).size() * 0.7);

        CollateralExtraction e = new CollateralExtraction();
        e.setApplicationReference(reference);
        e.setDocumentKind(kind);
        e.setCollateralType(KIND_TO_COLLATERAL_TYPE.get(kind));
        e.setSourceText(text.length() > 3900 ? text.substring(0, 3900) : text);
        e.setFields(fields);
        e.setMissingMandatory(missing);
        e.setSignals(signals);
        e.setOverallConfidence(Math.round(overall * 100.0) / 100.0);
        e.setExtractedBy("ai:collateral-extraction");
        CollateralExtraction saved = extractions.save(e);
        audit.ai("collateral-extraction", "COLLATERAL_EXTRACTED", "Application", reference,
                "Extracted %s from %s — %d field(s), %d missing-mandatory"
                        .formatted(KIND_TO_COLLATERAL_TYPE.get(kind), kind, fields.size(), missing.size()),
                Map.of("documentKind", kind, "fields", fields.size(),
                        "missingMandatory", missing.size(), "advisory", true));
        return saved;
    }

    @Transactional(readOnly = true)
    public List<CollateralExtraction> list(String reference) {
        return extractions.findByApplicationReferenceOrderByIdDesc(reference);
    }

    @Transactional
    public Collateral confirm(Long id, ConfirmCollateralExtractionRequest edits, String actor) {
        CollateralExtraction e = extractions.findById(id)
                .orElseThrow(() -> ApiException.notFound("No extraction: " + id));
        if (!"SUGGESTED".equals(e.getStatus())) {
            throw ApiException.conflict("Extraction already " + e.getStatus());
        }

        Map<String, Object> f = e.getFields() == null ? Map.of() : e.getFields();
        String description = pick(edits == null ? null : edits.description(),
                stringField(f, "description", defaultDescription(e, f)));
        Double marketValue = edits != null && edits.marketValue() != null ? edits.marketValue() : doubleField(f, "marketValue");
        if (marketValue == null || marketValue <= 0) {
            throw ApiException.badRequest("Cannot confirm: marketValue missing — supply one in the confirm body");
        }
        String valuationDate = pick(edits == null ? null : edits.valuationDate(), stringField(f, "valuationDate", null));
        String valuationSource = pick(edits == null ? null : edits.valuationSource(),
                stringField(f, "valuerName", "ai:collateral-extraction"));
        String owner = pick(edits == null ? null : edits.owner(), stringField(f, "registeredOwner", null));
        String location = pick(edits == null ? null : edits.location(), stringField(f, "addressLine", null));
        String perfectionStatus = pick(edits == null ? null : edits.perfectionStatus(), "IN_PROGRESS");
        double haircut = edits != null && edits.haircut() != null ? edits.haircut() : 0.0; // OriginationService applies its default

        Long facilityId = edits == null ? null : edits.facilityId();
        AddCollateralRequest req = new AddCollateralRequest(
                e.getCollateralType(), description, marketValue, valuationDate, valuationSource,
                haircut, owner, location, perfectionStatus, facilityId);
        Collateral created = origination.addCollateral(e.getApplicationReference(), req, actor);

        e.setStatus("CONFIRMED");
        e.setLinkedCollateralId(created.getId());
        e.setReviewedBy(actor);
        e.setReviewedAt(Instant.now());
        e.setReviewNote(edits == null ? null : edits.note());
        extractions.save(e);
        audit.human(actor, "COLLATERAL_EXTRACTION_CONFIRMED", "Application", e.getApplicationReference(),
                "Confirmed %s extraction #%d -> collateral #%d".formatted(e.getCollateralType(), id, created.getId()),
                Map.of("extractionId", id, "collateralId", created.getId()));
        return created;
    }

    @Transactional
    public CollateralExtraction reject(Long id, String note, String actor) {
        CollateralExtraction e = extractions.findById(id)
                .orElseThrow(() -> ApiException.notFound("No extraction: " + id));
        if (!"SUGGESTED".equals(e.getStatus())) {
            throw ApiException.conflict("Extraction already " + e.getStatus());
        }
        e.setStatus("REJECTED");
        e.setReviewedBy(actor);
        e.setReviewedAt(Instant.now());
        e.setReviewNote(note);
        CollateralExtraction saved = extractions.save(e);
        audit.human(actor, "COLLATERAL_EXTRACTION_REJECTED", "Application", e.getApplicationReference(),
                "Rejected collateral extraction #" + id, Map.of("extractionId", id));
        return saved;
    }

    // ================================================================ 2. revaluation + LTV alerts

    @Transactional
    public CollateralRevaluation revalue(Long collateralId, RevalueRequest req, String actor) {
        Collateral c = collaterals.findById(collateralId)
                .orElseThrow(() -> ApiException.notFound("No collateral: " + collateralId));
        LoanApplication app = applications.findById(c.getApplicationId())
                .orElseThrow(() -> ApiException.notFound("No application for collateral"));
        if (req.newMarketValue() <= 0) {
            // A complete wipeout isn't a revaluation; it's a write-off and must take a
            // different path. Refusing here keeps the LTV math + audit summary coherent.
            throw ApiException.badRequest(
                    "newMarketValue must be positive — route a full write-off through the collateral release path");
        }
        double threshold = req.ltvThreshold() == null ? DEFAULT_LTV_THRESHOLD : req.ltvThreshold();

        double prev = c.getMarketValue();
        double newMv = req.newMarketValue();
        double drawn = req.drawnExposure();
        double ltvBefore = prev <= 0 ? 0.0 : drawn / prev;
        double ltvAfter = drawn / newMv;
        boolean breached = ltvAfter > threshold;
        String severity = !breached ? "INFO" : ltvAfter > threshold * 1.10 ? "BREACH" : "WARN";

        LocalDate effective;
        try {
            effective = req.effectiveDate() == null || req.effectiveDate().isBlank()
                    ? LocalDate.now() : LocalDate.parse(req.effectiveDate());
        } catch (DateTimeParseException ex) {
            throw ApiException.badRequest("Invalid effectiveDate; expected ISO yyyy-MM-dd");
        }

        CollateralRevaluation r = new CollateralRevaluation();
        r.setCollateralId(collateralId);
        r.setApplicationReference(app.getReference());
        r.setPreviousMarketValue(prev);
        r.setNewMarketValue(newMv);
        r.setDrawnExposure(drawn);
        r.setLtvBefore(round(ltvBefore));
        r.setLtvAfter(round(ltvAfter));
        r.setTrigger(req.trigger() == null ? "VALUATION_UPDATE" : req.trigger().toUpperCase());
        r.setEffectiveDate(effective);
        r.setLtvBreached(breached);
        r.setLtvThreshold(threshold);
        r.setAlertSeverity(severity);
        r.setNote(req.note());
        r.setTriggeredBy(actor);
        CollateralRevaluation saved = revaluations.save(r);
        audit.ai("collateral-monitor", "COLLATERAL_REVALUED", "Application", app.getReference(),
                "Collateral #%d revalued %.0f -> %.0f (LTV %.2f -> %.2f, threshold %.2f, %s)"
                        .formatted(collateralId, prev, newMv, ltvBefore, ltvAfter, threshold, severity),
                Map.of("collateralId", collateralId, "ltvAfter", r.getLtvAfter(),
                        "threshold", threshold, "severity", severity,
                        "breached", breached, "advisory", true));
        return saved;
    }

    /** Human gate: apply the revaluation to the live collateral row (or reject it). */
    @Transactional
    public CollateralRevaluation review(Long revaluationId, boolean apply, String note, String actor) {
        CollateralRevaluation r = revaluations.findById(revaluationId)
                .orElseThrow(() -> ApiException.notFound("No revaluation: " + revaluationId));
        if (!"PENDING".equals(r.getConfirmStatus())) {
            throw ApiException.conflict("Revaluation already " + r.getConfirmStatus());
        }
        r.setReviewedBy(actor);
        r.setReviewedAt(Instant.now());
        if (apply) {
            Collateral c = collaterals.findById(r.getCollateralId())
                    .orElseThrow(() -> ApiException.notFound("No collateral"));
            c.setMarketValue(r.getNewMarketValue());
            c.setValuationDate(r.getEffectiveDate());
            collaterals.save(c);
            r.setConfirmStatus("APPLIED");
            audit.human(actor, "COLLATERAL_REVALUATION_APPLIED", "Application", r.getApplicationReference(),
                    "Applied new MV %.0f to collateral #%d (LTV %.2f)"
                            .formatted(r.getNewMarketValue(), r.getCollateralId(), r.getLtvAfter()),
                    Map.of("revaluationId", revaluationId, "newMarketValue", r.getNewMarketValue()));
        } else {
            r.setConfirmStatus("REJECTED");
            audit.human(actor, "COLLATERAL_REVALUATION_REJECTED", "Application", r.getApplicationReference(),
                    "Rejected revaluation #" + revaluationId, Map.of("revaluationId", revaluationId, "note", String.valueOf(note)));
        }
        return revaluations.save(r);
    }

    @Transactional(readOnly = true)
    public List<CollateralRevaluation> revaluationsFor(String reference) {
        return revaluations.findByApplicationReferenceOrderByIdDesc(reference);
    }

    // ================================================================ 3. charge-Excel

    // Not readOnly — the method stamps audit.engine which writes a row into audit_events.
    @Transactional
    public String chargeExcel(String reference) {
        LoanApplication app = applications.findByReference(reference)
                .orElseThrow(() -> ApiException.notFound("No application: " + reference));
        List<Collateral> all = collaterals.findByApplicationId(app.getId());
        Map<Long, ProposedFacility> facsById = new LinkedHashMap<>();
        for (ProposedFacility f : facilities.findByApplicationIdOrderByOrdinalAsc(app.getId())) {
            facsById.put(f.getId(), f);
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Application,Borrower,Collateral ID,Type,Description,Owner,Location,")
          .append("Market Value,Haircut,Effective Value,Valuation Date,Valuation Source,")
          .append("Perfection Status,Perfection Date,Linked Facility\n");
        for (Collateral c : all) {
            String facRef = c.getFacilityId() == null ? "POOLED"
                    : facsById.containsKey(c.getFacilityId()) ? facsById.get(c.getFacilityId()).getReference()
                    : "FAC-" + c.getFacilityId();
            sb.append(csv(reference)).append(",")
              .append(csv(app.getCounterpartyName())).append(",")
              .append(c.getId()).append(",")
              .append(csv(c.getCollateralType())).append(",")
              .append(csv(c.getDescription())).append(",")
              .append(csv(c.getOwner())).append(",")
              .append(csv(c.getLocation())).append(",")
              .append(String.format(Locale.UK, "%.2f", c.getMarketValue())).append(",")
              .append(String.format(Locale.UK, "%.4f", c.getHaircut())).append(",")
              .append(String.format(Locale.UK, "%.2f", c.effectiveValue())).append(",")
              .append(c.getValuationDate() == null ? "" : c.getValuationDate()).append(",")
              .append(csv(c.getValuationSource())).append(",")
              .append(csv(c.getPerfectionStatus())).append(",")
              .append(c.getPerfectionDate() == null ? "" : c.getPerfectionDate()).append(",")
              .append(csv(facRef)).append("\n");
        }
        audit.engine("CHARGE_EXCEL_GENERATED", "Application", reference,
                "Generated charge-Excel for %d collateral row(s)".formatted(all.size()),
                Map.of("rows", all.size()));
        return sb.toString();
    }

    // ================================================================ parsers

    private static void parseValuation(String text, Map<String, Object> fields, List<String> signals) {
        labelled(text, "market value", fields, "marketValue", signals);
        labelled(text, "distressed sale value", fields, "distressedValue", signals);
        labelled(text, "valuation date", fields, "valuationDate", signals);
        labelled(text, "valuer", fields, "valuerName", signals);
        labelled(text, "address", fields, "addressLine", signals);
        labelled(text, "area", fields, "areaSqft", signals);
        if (!fields.containsKey("marketValue")) {
            // fall back: largest money mention
            Double biggest = largestMoney(text);
            if (biggest != null) {
                fields.put("marketValue", money(biggest, 0.7));
                signals.add("marketValue inferred as largest money figure");
            }
        }
    }

    private static void parseTitleDeed(String text, Map<String, Object> fields, List<String> signals) {
        labelled(text, "registered owner", fields, "registeredOwner", signals);
        labelled(text, "owner", fields, "registeredOwner", signals);
        labelled(text, "address", fields, "addressLine", signals);
        labelled(text, "registration no", fields, "registrationNo", signals);
        labelled(text, "registration number", fields, "registrationNo", signals);
        labelled(text, "property type", fields, "propertyType", signals);
        labelled(text, "purchase date", fields, "purchaseDate", signals);
        labelled(text, "area", fields, "areaSqft", signals);
    }

    private static void parseInsurance(String text, Map<String, Object> fields, List<String> signals) {
        labelled(text, "policy no", fields, "policyNo", signals);
        labelled(text, "policy number", fields, "policyNo", signals);
        labelled(text, "type of policy", fields, "policyType", signals);
        labelled(text, "insurer", fields, "insurerName", signals);
        labelled(text, "beneficiary", fields, "beneficiary", signals);
        labelled(text, "sum insured", fields, "sumInsured", signals);
        labelled(text, "premium", fields, "premium", signals);
        labelled(text, "valid until", fields, "validUntil", signals);
        labelled(text, "validity", fields, "validUntil", signals);
    }

    private static void parseVehicleRc(String text, Map<String, Object> fields, List<String> signals) {
        labelled(text, "identification no", fields, "identificationNo", signals);
        labelled(text, "vin", fields, "identificationNo", signals);
        labelled(text, "chassis", fields, "identificationNo", signals);
        labelled(text, "make", fields, "make", signals);
        labelled(text, "model", fields, "model", signals);
        labelled(text, "year", fields, "yearOfManufacture", signals);
        labelled(text, "registration no", fields, "registrationNo", signals);
        labelled(text, "registration date", fields, "registrationDate", signals);
        labelled(text, "registered owner", fields, "registeredOwner", signals);
        labelled(text, "owner", fields, "registeredOwner", signals);
        labelled(text, "invoice value", fields, "invoiceValue", signals);
        if (!fields.containsKey("yearOfManufacture")) {
            Matcher m = YEAR.matcher(text);
            if (m.find()) {
                fields.put("yearOfManufacture", scalar(m.group(), 0.6));
                signals.add("year inferred from 4-digit year token");
            }
        }
    }

    private static void parseBondCert(String text, Map<String, Object> fields, List<String> signals) {
        labelled(text, "isin", fields, "isin", signals);
        labelled(text, "issuer", fields, "issuerName", signals);
        labelled(text, "face value", fields, "faceValue", signals);
        labelled(text, "coupon", fields, "couponRate", signals);
        labelled(text, "maturity", fields, "maturityDate", signals);
    }

    private static void parsePgDeed(String text, Map<String, Object> fields, List<String> signals) {
        labelled(text, "guarantor", fields, "guarantorName", signals);
        labelled(text, "guarantee amount", fields, "guaranteeAmount", signals);
        labelled(text, "amount of guarantee", fields, "guaranteeAmount", signals);
        labelled(text, "type of guarantee", fields, "guaranteeType", signals);
        labelled(text, "personal guarantee", fields, "guaranteeType", signals);
        labelled(text, "passport", fields, "passportNo", signals);
    }

    /** Find a labelled field in the document text — "Label: value" / "Label = value" / "Label - value". */
    private static void labelled(String text, String label, Map<String, Object> fields,
                                 String key, List<String> signals) {
        if (fields.containsKey(key)) return;       // first hit wins
        Pattern p = Pattern.compile(
                "(?i)\\b" + Pattern.quote(label) + "\\b\\s*[:=\\-]\\s*([^\\n\\r]+?)(?=\\s{2,}|[\\n\\r;|]|$)");
        Matcher m = p.matcher(text);
        if (!m.find()) return;
        String raw = m.group(1).trim().replaceAll("[\\s,]+$", "");
        Object value = coerce(raw);
        fields.put(key, valueWithConfidence(value, 0.85));
        signals.add(label + " → " + raw);
    }

    private static Object coerce(String raw) {
        // Dates first — they share digit tokens with money but must not be misread as numbers.
        Matcher iso = DATE_ISO.matcher(raw);
        if (iso.matches()) {
            return "%04d-%02d-%02d".formatted(Integer.parseInt(iso.group(1)),
                    Integer.parseInt(iso.group(2)), Integer.parseInt(iso.group(3)));
        }
        Matcher dmy = DATE_DMY.matcher(raw);
        if (dmy.matches()) {
            return "%04d-%02d-%02d".formatted(Integer.parseInt(dmy.group(3)),
                    Integer.parseInt(dmy.group(2)), Integer.parseInt(dmy.group(1)));
        }
        // Then money — require a full match so partial digit prefixes don't sneak through.
        Double parsed = parseMoney(raw);
        if (parsed != null) return parsed;
        return raw;
    }

    /**
     * Parse a money expression that may use US/UK grouping ("4,800,000"), Indian grouping
     * ("4,80,00,000" = 4 crore 80 lakh), bare numbers, or a magnitude unit (crore / lakh /
     * million / billion). Returns null when the string isn't a money expression.
     */
    private static Double parseMoney(String raw) {
        Matcher mn = MONEY.matcher(raw);
        if (!mn.matches()) {
            // Indian groupings (1-2 digit head + repeated 2-digit groups + 3-digit tail) don't
            // satisfy the (?:[ ,][0-9]{3})+ pattern. Re-attempt by stripping group separators
            // and re-matching the digits-and-unit shape.
            String stripped = raw.replaceAll("[\\s,]", "");
            Matcher loose = MONEY.matcher(stripped);
            if (!loose.matches()) return null;
            mn = loose;
        }
        try {
            double v = Double.parseDouble(mn.group(1).replace(",", "").replace(" ", ""));
            String unit = mn.group(2);
            if (unit != null) {
                String u = unit.toLowerCase();
                if (u.startsWith("cr") || u.startsWith("crore")) v *= 10_000_000;
                else if (u.startsWith("lak") || u.startsWith("lac")) v *= 100_000;
                else if (u.startsWith("mn") || u.startsWith("million") || u.equals("m")) v *= 1_000_000;
                else if (u.startsWith("bn") || u.startsWith("billion")) v *= 1_000_000_000;
            }
            return v;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    /**
     * Finds the largest money expression in the text. Scans every digit-run together with
     * an optional trailing unit (crore / lakh / million / billion) via the same MONEY
     * regex used by {@link #parseMoney}, and keeps the maximum. Robust to single-spaced
     * sentences (a previous token-split approach missed those) and to Indian groupings.
     */
    private static Double largestMoney(String text) {
        double best = 0.0;
        boolean any = false;
        Matcher m = MONEY.matcher(text);
        while (m.find()) {
            try {
                double v = Double.parseDouble(m.group(1).replace(",", "").replace(" ", ""));
                String unit = m.group(2);
                if (unit != null) {
                    String u = unit.toLowerCase();
                    if (u.startsWith("cr") || u.startsWith("crore")) v *= 10_000_000;
                    else if (u.startsWith("lak") || u.startsWith("lac")) v *= 100_000;
                    else if (u.startsWith("mn") || u.startsWith("million") || u.equals("m")) v *= 1_000_000;
                    else if (u.startsWith("bn") || u.startsWith("billion")) v *= 1_000_000_000;
                }
                if (v > best) { best = v; any = true; }
            } catch (NumberFormatException ignored) { /* skip */ }
        }
        return any ? best : null;
    }

    // ================================================================ helpers

    private static Map<String, Object> valueWithConfidence(Object value, double confidence) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("value", value);
        m.put("confidence", confidence);
        return m;
    }

    private static Map<String, Object> money(double v, double confidence) {
        return valueWithConfidence(v, confidence);
    }

    private static Map<String, Object> scalar(Object v, double confidence) {
        return valueWithConfidence(v, confidence);
    }

    @SuppressWarnings("unchecked")
    private static String stringField(Map<String, Object> fields, String key, String fallback) {
        Object o = fields.get(key);
        if (o == null) return fallback;
        if (o instanceof Map<?, ?> m) {
            Object v = ((Map<String, Object>) m).get("value");
            return v == null ? fallback : v.toString();
        }
        return o.toString();
    }

    @SuppressWarnings("unchecked")
    private static Double doubleField(Map<String, Object> fields, String key) {
        Object o = fields.get(key);
        if (o == null) return null;
        if (o instanceof Map<?, ?> m) {
            Object v = ((Map<String, Object>) m).get("value");
            if (v instanceof Number n) return n.doubleValue();
            try { return v == null ? null : Double.parseDouble(v.toString()); }
            catch (NumberFormatException e) { return null; }
        }
        if (o instanceof Number n) return n.doubleValue();
        try { return Double.parseDouble(o.toString()); }
        catch (NumberFormatException e) { return null; }
    }

    private static String defaultDescription(CollateralExtraction e, Map<String, Object> f) {
        String addr = stringField(f, "addressLine", null);
        if (addr != null) return e.getCollateralType() + " · " + addr;
        String issuer = stringField(f, "issuerName", null);
        if (issuer != null) return e.getCollateralType() + " · " + issuer;
        String policy = stringField(f, "policyNo", null);
        if (policy != null) return "Policy " + policy;
        String make = stringField(f, "make", null);
        if (make != null) return make + " " + stringField(f, "model", "");
        return e.getCollateralType();
    }

    private static String pick(String override, String fallback) {
        return override == null || override.isBlank() ? fallback : override;
    }

    private static String csv(String s) {
        if (s == null) return "";
        // Defang spreadsheet-formula triggers (OWASP CSV-injection): prefix with apostrophe
        // when the cell would otherwise begin with =, +, -, @, TAB, or CR — Excel / Sheets /
        // LibreOffice evaluate these even inside quoted CSV fields.
        String safe = s;
        if (!safe.isEmpty()) {
            char first = safe.charAt(0);
            if (first == '=' || first == '+' || first == '-' || first == '@'
                    || first == '\t' || first == '\r') {
                safe = "'" + safe;
            }
        }
        boolean needs = safe.contains(",") || safe.contains("\"") || safe.contains("\n");
        String escaped = safe.replace("\"", "\"\"");
        return needs ? "\"" + escaped + "\"" : escaped;
    }

    private static double round(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }
}
