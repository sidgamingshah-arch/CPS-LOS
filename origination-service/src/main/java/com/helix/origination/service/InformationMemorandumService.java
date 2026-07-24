package com.helix.origination.service;

import com.helix.common.audit.AuditService;
import com.helix.common.web.ApiException;
import com.helix.origination.dto.SyndicationDtos.SyndicateBook;
import com.helix.origination.entity.Collateral;
import com.helix.origination.entity.DealStructure;
import com.helix.origination.entity.FinancialPeriod;
import com.helix.origination.entity.InformationMemorandum;
import com.helix.origination.entity.LoanApplication;
import com.helix.origination.entity.ProposedFacility;
import com.helix.origination.repo.CollateralRepository;
import com.helix.origination.repo.DealStructureRepository;
import com.helix.origination.repo.FinancialPeriodRepository;
import com.helix.origination.repo.InformationMemorandumRepository;
import com.helix.origination.repo.LoanApplicationRepository;
import com.helix.origination.repo.ProposedFacilityRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Syndication Information-Memorandum workspace (CLoM gap #80 / R3-07). The IM is a
 * <b>versioned document artifact</b> layered on top of an existing SYNDICATION deal.
 * Sections are seeded <b>deterministically</b> from the deal's own data (application,
 * deal structure, the syndicate book) — no LLM in the path — and edited by named
 * humans through the DRAFT → CIRCULATED → FINAL lifecycle (WITHDRAWN as an off-ramp).
 *
 * <p>The governance invariant: an IM never writes to any authoritative figure. It reads
 * the syndicate book / facilities / collateral / spread to compose prose, but it only
 * ever persists into its own {@link InformationMemorandum} rows. Allocations, participant
 * commitments, fees, ratings and pricing are byte-identical before and after any IM op.</p>
 */
@Service
public class InformationMemorandumService {

    /** The standard sections seeded on a new IM, in presentation order. */
    private static final String[][] STANDARD_SECTIONS = {
            {"EXECUTIVE_SUMMARY", "Executive Summary"},
            {"TRANSACTION_OVERVIEW", "Transaction Overview"},
            {"BORROWER_PROFILE", "Borrower Profile"},
            {"FACILITY_AND_SECURITY", "Facility & Security"},
            {"FINANCIALS_SUMMARY", "Financials Summary"},
            {"RISK_FACTORS", "Risk Factors"},
            {"SYNDICATION_TERMS", "Syndication Terms"},
    };

    private final InformationMemorandumRepository memoranda;
    private final DealStructureRepository structures;
    private final LoanApplicationRepository applications;
    private final ProposedFacilityRepository facilities;
    private final CollateralRepository collaterals;
    private final FinancialPeriodRepository periods;
    private final SyndicationService syndication;
    private final AuditService audit;

    public InformationMemorandumService(InformationMemorandumRepository memoranda,
                                        DealStructureRepository structures,
                                        LoanApplicationRepository applications,
                                        ProposedFacilityRepository facilities,
                                        CollateralRepository collaterals,
                                        FinancialPeriodRepository periods,
                                        SyndicationService syndication,
                                        AuditService audit) {
        this.memoranda = memoranda;
        this.structures = structures;
        this.applications = applications;
        this.facilities = facilities;
        this.collaterals = collaterals;
        this.periods = periods;
        this.syndication = syndication;
        this.audit = audit;
    }

    // ============================================================ create

    @Transactional
    public InformationMemorandum create(String reference, String title, String actor) {
        requireActor(actor, "create an Information Memorandum");
        DealStructure structure = structures.findByApplicationReference(reference)
                .orElseThrow(() -> ApiException.notFound("No deal structure for " + reference));
        if (!"SYNDICATION".equalsIgnoreCase(structure.getStructureType())) {
            throw ApiException.badRequest(reference + " is not a SYNDICATION deal (structure="
                    + structure.getStructureType() + ") — an IM is only for syndicated facilities");
        }
        int nextVersion = memoranda.findByApplicationReferenceOrderByVersionDescIdDesc(reference).stream()
                .mapToInt(InformationMemorandum::getVersion).max().orElse(0) + 1;

        InformationMemorandum im = new InformationMemorandum();
        im.setApplicationReference(reference);
        im.setSyndicationRef(reference);
        im.setVersion(nextVersion);
        im.setImRef("IM-" + reference + "-V" + nextVersion);
        im.setTitle(resolveTitle(title, reference));
        im.setStatus("DRAFT");
        im.setSections(seedSections(reference, structure));
        im.setCreatedBy(actor);
        InformationMemorandum saved = memoranda.save(im);

        audit.human(actor, "SYNDICATION_IM_CREATED", "InformationMemorandum", saved.getImRef(),
                "Created IM %s (v%d) for syndication deal %s".formatted(saved.getImRef(), nextVersion, reference),
                Map.of("reference", reference, "imRef", saved.getImRef(), "version", nextVersion,
                        "sections", saved.getSections().size()));
        return saved;
    }

    // ============================================================ section upsert

    @Transactional
    public InformationMemorandum upsertSection(Long id, String key, String content, String actor) {
        requireActor(actor, "edit an Information Memorandum section");
        InformationMemorandum im = get(id);
        if (!("DRAFT".equals(im.getStatus()) || "CIRCULATED".equals(im.getStatus()))) {
            throw ApiException.conflict("IM " + im.getImRef() + " is " + im.getStatus()
                    + " — sections can only be edited while DRAFT or CIRCULATED");
        }
        if (key == null || key.isBlank()) {
            throw ApiException.badRequest("section key is required");
        }
        String normalisedKey = key.trim().toUpperCase().replace(' ', '_');
        Map<String, Object> next = new LinkedHashMap<>(im.getSections() == null ? Map.of() : im.getSections());

        int order;
        String sectionTitle;
        Object existing = next.get(normalisedKey);
        if (existing instanceof Map<?, ?> ex) {
            order = intOf(ex.get("order"), next.size());
            sectionTitle = ex.get("title") == null ? humanise(normalisedKey) : String.valueOf(ex.get("title"));
        } else {
            order = next.values().stream()
                    .filter(v -> v instanceof Map)
                    .mapToInt(v -> intOf(((Map<?, ?>) v).get("order"), 0)).max().orElse(-1) + 1;
            sectionTitle = humanise(normalisedKey);
        }
        next.put(normalisedKey, section(order, sectionTitle, content == null ? "" : content, "human-edited"));
        im.setSections(next);
        InformationMemorandum saved = memoranda.save(im);

        audit.human(actor, "SYNDICATION_IM_SECTION_UPSERTED", "InformationMemorandum", saved.getImRef(),
                "Upserted section %s on IM %s".formatted(normalisedKey, saved.getImRef()),
                Map.of("imRef", saved.getImRef(), "sectionKey", normalisedKey));
        return saved;
    }

    // ============================================================ lifecycle transitions

    @Transactional
    public InformationMemorandum circulate(Long id, String actor) {
        requireActor(actor, "circulate an Information Memorandum");
        InformationMemorandum im = get(id);
        if (!"DRAFT".equals(im.getStatus())) {
            throw ApiException.conflict("IM " + im.getImRef() + " is " + im.getStatus()
                    + " — only a DRAFT can be circulated");
        }
        im.setStatus("CIRCULATED");
        im.setCirculatedBy(actor);
        im.setCirculatedAt(Instant.now());
        InformationMemorandum saved = memoranda.save(im);
        audit.human(actor, "SYNDICATION_IM_CIRCULATED", "InformationMemorandum", saved.getImRef(),
                "Circulated IM %s (v%d) to syndicate for %s".formatted(
                        saved.getImRef(), saved.getVersion(), saved.getApplicationReference()),
                Map.of("imRef", saved.getImRef(), "reference", saved.getApplicationReference()));
        return saved;
    }

    @Transactional
    public InformationMemorandum finalise(Long id, String actor) {
        requireActor(actor, "finalise an Information Memorandum");
        InformationMemorandum im = get(id);
        if (!"CIRCULATED".equals(im.getStatus())) {
            throw ApiException.conflict("IM " + im.getImRef() + " is " + im.getStatus()
                    + " — only a CIRCULATED IM can be finalised");
        }
        // Segregation of duties: the finaliser must differ from the drafter.
        if (actor.equals(im.getCreatedBy())) {
            throw ApiException.forbiddenAutonomy(
                    "IM finalisation must be made by a different actor than the drafter ("
                    + im.getCreatedBy() + ")");
        }
        im.setStatus("FINAL");
        im.setFinalisedBy(actor);
        im.setFinalisedAt(Instant.now());
        InformationMemorandum saved = memoranda.save(im);
        audit.human(actor, "SYNDICATION_IM_FINALISED", "InformationMemorandum", saved.getImRef(),
                "Finalised IM %s (v%d) for %s".formatted(
                        saved.getImRef(), saved.getVersion(), saved.getApplicationReference()),
                Map.of("imRef", saved.getImRef(), "reference", saved.getApplicationReference(),
                        "version", saved.getVersion()));
        return saved;
    }

    @Transactional
    public InformationMemorandum withdraw(Long id, String actor) {
        requireActor(actor, "withdraw an Information Memorandum");
        InformationMemorandum im = get(id);
        if ("WITHDRAWN".equals(im.getStatus())) {
            throw ApiException.conflict("IM " + im.getImRef() + " is already WITHDRAWN");
        }
        im.setStatus("WITHDRAWN");
        im.setWithdrawnBy(actor);
        im.setWithdrawnAt(Instant.now());
        InformationMemorandum saved = memoranda.save(im);
        audit.human(actor, "SYNDICATION_IM_WITHDRAWN", "InformationMemorandum", saved.getImRef(),
                "Withdrew IM %s (v%d) for %s".formatted(
                        saved.getImRef(), saved.getVersion(), saved.getApplicationReference()),
                Map.of("imRef", saved.getImRef(), "reference", saved.getApplicationReference()));
        return saved;
    }

    /**
     * Append-only re-draft: clone a pinned (FINAL/WITHDRAWN) IM into a fresh DRAFT at
     * {@code version + 1}, copying its sections. The source IM is left untouched — its
     * status, sections and version remain exactly as they were.
     */
    @Transactional
    public InformationMemorandum redraft(Long id, String actor) {
        requireActor(actor, "re-draft an Information Memorandum");
        InformationMemorandum source = get(id);
        if (!("FINAL".equals(source.getStatus()) || "WITHDRAWN".equals(source.getStatus()))) {
            throw ApiException.conflict("IM " + source.getImRef() + " is " + source.getStatus()
                    + " — only a FINAL or WITHDRAWN IM is re-drafted into a new version (edit a live DRAFT in place)");
        }
        String reference = source.getApplicationReference();
        int nextVersion = memoranda.findByApplicationReferenceOrderByVersionDescIdDesc(reference).stream()
                .mapToInt(InformationMemorandum::getVersion).max().orElse(source.getVersion()) + 1;

        InformationMemorandum im = new InformationMemorandum();
        im.setApplicationReference(reference);
        im.setSyndicationRef(source.getSyndicationRef());
        im.setVersion(nextVersion);
        im.setImRef("IM-" + reference + "-V" + nextVersion);
        im.setTitle(source.getTitle());
        im.setStatus("DRAFT");
        im.setSections(new LinkedHashMap<>(source.getSections() == null ? Map.of() : source.getSections()));
        im.setSupersedesImRef(source.getImRef());
        im.setCreatedBy(actor);
        InformationMemorandum saved = memoranda.save(im);

        audit.human(actor, "SYNDICATION_IM_REDRAFTED", "InformationMemorandum", saved.getImRef(),
                "Re-drafted IM %s (v%d) from %s".formatted(
                        saved.getImRef(), nextVersion, source.getImRef()),
                Map.of("imRef", saved.getImRef(), "supersedesImRef", source.getImRef(),
                        "reference", reference, "version", nextVersion));
        return saved;
    }

    // ============================================================ reads

    @Transactional(readOnly = true)
    public List<InformationMemorandum> listForDeal(String reference) {
        return memoranda.findByApplicationReferenceOrderByVersionDescIdDesc(reference);
    }

    @Transactional(readOnly = true)
    public InformationMemorandum get(Long id) {
        return memoranda.findById(id)
                .orElseThrow(() -> ApiException.notFound("No Information Memorandum: " + id));
    }

    // ============================================================ deterministic section grounding

    private Map<String, Object> seedSections(String reference, DealStructure structure) {
        LoanApplication app = applications.findByReference(reference).orElse(null);
        String ccy = app != null ? app.getCurrency() : "INR";
        String borrower = app != null ? app.getCounterpartyName() : reference;

        Map<String, Object> out = new LinkedHashMap<>();
        int order = 0;
        for (String[] def : STANDARD_SECTIONS) {
            String key = def[0];
            String sectionTitle = def[1];
            String content = switch (key) {
                case "EXECUTIVE_SUMMARY" -> executiveSummary(app, structure, borrower, ccy);
                case "TRANSACTION_OVERVIEW" -> transactionOverview(app, ccy, borrower);
                case "BORROWER_PROFILE" -> borrowerProfile(app, borrower);
                case "FACILITY_AND_SECURITY" -> facilityAndSecurity(app);
                case "FINANCIALS_SUMMARY" -> financialsSummary(app);
                case "RISK_FACTORS" -> riskFactors(app);
                case "SYNDICATION_TERMS" -> syndicationTerms(reference, ccy);
                default -> "";
            };
            out.put(key, section(order++, sectionTitle, content, "deterministic:deal-data"));
        }
        return out;
    }

    private String executiveSummary(LoanApplication app, DealStructure s, String borrower, String ccy) {
        double total = s.getTotalDealAmount() > 0 ? s.getTotalDealAmount()
                : (app != null ? app.getRequestedAmount() : 0);
        String arranger = s.getLeadArranger() == null || s.getLeadArranger().isBlank()
                ? "the lead arranger" : s.getLeadArranger();
        String facilityType = app != null ? app.getFacilityType() : "credit";
        String purpose = app != null && app.getPurpose() != null ? app.getPurpose() : "general corporate purposes";
        return ("This Information Memorandum invites participation in a syndicated %s facility for %s. "
                + "%s is arranging a total facility of %s. Purpose: %s.")
                .formatted(facilityType, borrower, arranger, money(total, ccy), purpose);
    }

    private String transactionOverview(LoanApplication app, String ccy, String borrower) {
        if (app == null) return "Borrower: " + borrower + ". Transaction details pending capture.";
        return ("Borrower: %s. Facility type: %s. Requested amount: %s. Tenor: %d months. "
                + "Jurisdiction: %s. Segment: %s.")
                .formatted(borrower, app.getFacilityType(), money(app.getRequestedAmount(), ccy),
                        app.getTenorMonths(), app.getJurisdiction(), app.getSegment());
    }

    private String borrowerProfile(LoanApplication app, String borrower) {
        if (app == null) return borrower + " — profile pending.";
        String sector = app.getSector() == null || app.getSector().isBlank() ? "an unclassified" : app.getSector();
        return "%s operates in the %s sector (%s). Counterparty reference %s; domiciled in %s."
                .formatted(borrower, sector, app.getSegment(), app.getCounterpartyRef(), app.getJurisdiction());
    }

    private String facilityAndSecurity(LoanApplication app) {
        if (app == null) return "Facility & security pending capture.";
        StringBuilder sb = new StringBuilder();
        List<ProposedFacility> fac = facilities.findByApplicationIdOrderByOrdinalAsc(app.getId());
        if (fac.isEmpty()) {
            sb.append("Facility: %s of %s over %d months.".formatted(
                    app.getFacilityType(), money(app.getRequestedAmount(), app.getCurrency()), app.getTenorMonths()));
        } else {
            sb.append("Proposed facilities: ");
            List<String> lines = new ArrayList<>();
            for (ProposedFacility f : fac) {
                lines.add("%s %s (%dm)".formatted(f.getFacilityType(),
                        money(f.getAmount(), f.getCurrency()), f.getTenorMonths()));
            }
            sb.append(String.join("; ", lines)).append('.');
        }
        List<Collateral> col = collaterals.findByApplicationId(app.getId());
        if (col.isEmpty()) {
            sb.append(app.isSecured()
                    ? " Security: %s valued at %s.".formatted(
                            app.getCollateralType() == null ? "collateral" : app.getCollateralType(),
                            money(app.getCollateralValue(), app.getCurrency()))
                    : " Security: unsecured / clean.");
        } else {
            List<String> lines = new ArrayList<>();
            for (Collateral c : col) {
                lines.add("%s — %s valued at %s (%s)".formatted(c.getCollateralType(), c.getDescription(),
                        money(c.getMarketValue(), app.getCurrency()), c.getPerfectionStatus()));
            }
            sb.append(" Security: ").append(String.join("; ", lines)).append('.');
        }
        return sb.toString();
    }

    private String financialsSummary(LoanApplication app) {
        if (app == null) return "Financials pending spread.";
        List<FinancialPeriod> ps = periods.findByApplicationIdOrderByOrdinalAsc(app.getId());
        if (ps.isEmpty()) {
            return "No financial periods spread yet. The authoritative financials are the analyst-confirmed "
                    + "spread of record once captured.";
        }
        List<String> labels = new ArrayList<>();
        for (FinancialPeriod p : ps) {
            labels.add("%s (%s, %s)".formatted(p.getLabel(), p.getGaap(), p.getCurrency()));
        }
        return ("Financial periods on record: %s. Figures quoted in the IM are drawn verbatim from the "
                + "analyst-confirmed spread of record; the IM does not restate them.")
                .formatted(String.join(", ", labels));
    }

    private String riskFactors(LoanApplication app) {
        if (app == null) return "Risk factors pending.";
        String sector = app.getSector() == null || app.getSector().isBlank() ? "the borrower's" : app.getSector();
        return ("Key risk factors for participant consideration: (1) %s sector cyclicality and demand risk; "
                + "(2) tenor / refinancing risk over the %d-month term; (3) security position — the facility is %s; "
                + "(4) jurisdiction / regulatory risk (%s). Participants should form their own credit view.")
                .formatted(sector, app.getTenorMonths(), app.isSecured() ? "secured" : "unsecured",
                        app.getJurisdiction());
    }

    private String syndicationTerms(String reference, String ccy) {
        try {
            SyndicateBook b = syndication.book(reference);
            return ("Syndicated size: %s across %d lender(s); committed to date %s. Fee waterfall (indicative): "
                    + "arrangement %s, underwriting %s, agency %s, participation %s. Allocations and participant "
                    + "commitments shown here are the authoritative syndicate book and are not altered by this IM.")
                    .formatted(money(b.totalCommitment(), b.currency()), b.lenders().size(),
                            money(b.totalFunded(), b.currency()),
                            money(b.feeTotals().arrangementFee(), b.currency()),
                            money(b.feeTotals().underwritingFee(), b.currency()),
                            money(b.feeTotals().agencyFee(), b.currency()),
                            money(b.feeTotals().participationFee(), b.currency()));
        } catch (RuntimeException ex) {
            return "Syndicate participants pending capture — set the deal structure lenders to populate the "
                    + "syndication terms. Amounts are quoted in " + ccy + ".";
        }
    }

    // ============================================================ helpers

    private static Map<String, Object> section(int order, String title, String content, String source) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("title", title);
        m.put("content", content);
        m.put("order", order);
        m.put("source", source);
        return m;
    }

    private static String resolveTitle(String title, String reference) {
        return title == null || title.isBlank()
                ? "Information Memorandum — " + reference : title.trim();
    }

    private static void requireActor(String actor, String action) {
        if (actor == null || actor.isBlank()) {
            throw ApiException.forbiddenAutonomy("A named human actor is required to " + action);
        }
    }

    private static String humanise(String key) {
        String[] parts = key.toLowerCase().split("_");
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(Character.toUpperCase(p.charAt(0))).append(p.substring(1));
        }
        return sb.toString();
    }

    private static int intOf(Object o, int dflt) {
        if (o instanceof Number n) return n.intValue();
        if (o instanceof String s) try { return Integer.parseInt(s); } catch (NumberFormatException ignored) { }
        return dflt;
    }

    private static String money(double v, String ccy) {
        return String.format("%,.0f %s", v, ccy == null ? "" : ccy).trim();
    }
}
