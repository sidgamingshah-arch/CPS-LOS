package com.helix.decision.service;

import com.helix.common.audit.AuditService;
import com.helix.common.llm.LlmClient;
import com.helix.common.llm.LlmRequest;
import com.helix.common.llm.LlmResult;
import com.helix.common.web.ApiException;
import com.helix.decision.client.UpstreamClient;
import com.helix.decision.client.UpstreamClient.DealEnvelopeDto;
import com.helix.decision.client.UpstreamClient.MasterRecordDto;
import com.helix.decision.client.UpstreamClient.RiskSummaryDto;
import com.helix.decision.client.UpstreamClient.PeriodFinancialsDto;
import com.helix.decision.dto.GroupDtos.GroupInsights;
import com.helix.decision.dto.GroupDtos.GroupMemberInsight;
import com.helix.decision.entity.Annexure;
import com.helix.decision.entity.CadCase;
import com.helix.decision.entity.ChecklistItem;
import com.helix.decision.entity.ConditionPrecedent;
import com.helix.decision.entity.Covenant;
import com.helix.decision.entity.CreditDecision;
import com.helix.decision.entity.CreditProposal;
import com.helix.decision.entity.Deviation;
import com.helix.decision.entity.PerfectionCase;
import com.helix.decision.entity.PerfectionStep;
import com.helix.decision.repo.AnnexureRepository;
import com.helix.decision.repo.CadCaseRepository;
import com.helix.decision.repo.ChecklistItemRepository;
import com.helix.decision.repo.ConditionPrecedentRepository;
import com.helix.decision.repo.CovenantRepository;
import com.helix.decision.repo.CreditDecisionRepository;
import com.helix.decision.repo.CreditProposalRepository;
import com.helix.decision.repo.DeviationRepository;
import com.helix.decision.repo.PerfectionCaseRepository;
import com.helix.decision.repo.PerfectionStepRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Generates a formal credit proposal / committee memo (PRD §8 US-8.3). Strictly
 * grounded: every figure is quoted from the engines/services, with the source
 * endpoint stored in {@code citations}. No invention; AI never produces a
 * decision or a pricing figure — it merely composes a report that the named
 * approver edits and signs.
 */
@Service
public class CreditProposalService {

    private final CreditProposalRepository proposals;
    private final CovenantRepository covenants;
    private final CreditDecisionRepository decisions;
    private final UpstreamClient upstream;
    private final GroupInsightsService groupInsights;
    private final AuditService audit;
    private final LlmClient llm;
    // Local grounding sources for the richer bank-CAM sections (all read-only here).
    private final DeviationRepository deviations;
    private final CadCaseRepository cadCases;
    private final ChecklistItemRepository checklistItems;
    private final ConditionPrecedentRepository conditionPrecedents;
    private final AnnexureRepository annexures;
    private final PerfectionCaseRepository perfectionCases;
    private final PerfectionStepRepository perfectionSteps;
    /** Read-only source of human-CONFIRMED AI commentary, woven into the proposal (never a figure). */
    private final com.helix.decision.repo.ProposalCommentaryRepository commentary;

    public CreditProposalService(CreditProposalRepository proposals, CovenantRepository covenants,
                                 CreditDecisionRepository decisions, UpstreamClient upstream,
                                 GroupInsightsService groupInsights, AuditService audit,
                                 LlmClient llm,
                                 DeviationRepository deviations, CadCaseRepository cadCases,
                                 ChecklistItemRepository checklistItems,
                                 ConditionPrecedentRepository conditionPrecedents,
                                 AnnexureRepository annexures, PerfectionCaseRepository perfectionCases,
                                 PerfectionStepRepository perfectionSteps,
                                 com.helix.decision.repo.ProposalCommentaryRepository commentary) {
        this.proposals = proposals;
        this.covenants = covenants;
        this.decisions = decisions;
        this.upstream = upstream;
        this.groupInsights = groupInsights;
        this.audit = audit;
        this.llm = llm;
        this.deviations = deviations;
        this.cadCases = cadCases;
        this.checklistItems = checklistItems;
        this.conditionPrecedents = conditionPrecedents;
        this.annexures = annexures;
        this.perfectionCases = perfectionCases;
        this.perfectionSteps = perfectionSteps;
        this.commentary = commentary;
    }

    /** No-format entry point — resolves the deal-segment default (else STANDARD). Byte-identical to
     *  the pre-format behaviour for a standard corporate deal. */
    @Transactional
    public CreditProposal generate(String reference, String actor) {
        return generate(reference, null, actor);
    }

    /**
     * Generate the credit proposal under a chosen CAM {@code format}. The format's section SET,
     * ORDER and human titles come from a resolved {@code PROPOSAL_FORMAT} master; each section is
     * produced by a stable-key builder. Resolution: explicit {@code format} arg → else the deal
     * segment's default (ModelResolve-style most-specific pick) → else STANDARD. With no format the
     * resolved STANDARD layout is byte-identical to the pre-format universal proposal.
     *
     * <p>A format only reshapes the RENDERING — it is never a figure source. Every figure is quoted
     * from the same upstream services; the segment-specific builders render already-computed values
     * only (no new authoritative computation).</p>
     */
    @Transactional
    public CreditProposal generate(String reference, String format, String actor) {
        DealEnvelopeDto env = upstream.envelope(reference);
        RiskSummaryDto rs = upstream.riskSummary(reference);
        if (rs == null || rs.rating() == null) {
            throw ApiException.conflict("Cannot generate proposal — the deal must be rated first");
        }
        List<Covenant> covs = covenants.findByApplicationReference(reference);
        CreditDecision dec = decisions.findFirstByApplicationReferenceOrderByCreatedAtDesc(reference).orElse(null);

        ResolvedFormat fmt = resolveFormat(format, env.segment());

        // Optional advisory LLM narrative, grounded in the SAME figures rendered below (every figure
        // quoted verbatim in the prompt). When a provider is configured it adds ONE extra executive-
        // narrative prose section; the deterministic sections, structured citations, versioning and
        // the confirm gate are unchanged. Provider 'none' (default) → no section, markdown/html
        // byte-identical to today.
        LlmResult proposalDraft = llmNarrative(env, rs, covs, dec);
        boolean llmDrafted = proposalDraft.usable();

        Md md = new Md();
        md.h1("Credit proposal · " + env.applicationReference());
        md.muted("Prepared by Helix · grounded in platform data · awaiting named human review and sign-off");
        md.spacer();
        if (llmDrafted) {
            md.h2("Executive narrative (AI-drafted, advisory)");
            md.line(proposalDraft.text().strip());
        }

        // Assemble ONLY the resolved format's sections, in that order, via the keyed builders.
        // When the format does not list a standalone `sublimits` section, the sublimits block is
        // rendered inline right after the facilities table — exactly as the universal layout did.
        Ctx ctx = new Ctx(env, rs, covs, dec);
        Set<String> keys = new LinkedHashSet<>();
        for (Section s : fmt.sections()) keys.add(s.key());
        boolean inlineSublimits = !keys.contains("sublimits");
        for (Section s : fmt.sections()) {
            renderSection(s.key(), md, ctx, inlineSublimits);
        }
        // Fold human-CONFIRMED AI commentary INTO the proposal (no separate module).
        renderConfirmedCommentary(md, reference);

        Map<String, Object> citations = new LinkedHashMap<>();
        citations.put("envelope", "origination-service GET /api/applications/" + reference + "/envelope");
        citations.put("rating",   "risk-service GET /api/risk/" + reference + "/rating");
        citations.put("capital",  "risk-service GET /api/risk/" + reference + "/capital");
        citations.put("pricing",  "risk-service GET /api/risk/" + reference);
        citations.put("covenants","decision-service GET /api/decisions/" + reference + "/covenants");
        citations.put("decision", "decision-service GET /api/decisions/" + reference);

        int version = proposals.findFirstByApplicationReferenceOrderByVersionDesc(reference)
                .map(p -> p.getVersion() + 1).orElse(1);

        CreditProposal p = new CreditProposal();
        p.setApplicationReference(reference);
        p.setVersion(version);
        p.setMarkdown(md.markdown());
        p.setHtml(md.html());
        p.setCitations(citations);
        p.setSections(fmt.sections().stream().map(Section::title).toList());
        p.setFormat(fmt.code());
        p.setGeneratedBy(actor);
        CreditProposal saved = proposals.save(p);
        Map<String, Object> propDetail = new LinkedHashMap<>();
        propDetail.put("version", version);
        propDetail.put("format", fmt.code());
        propDetail.put("facilities", env.facilities() == null ? 0 : env.facilities().size());
        propDetail.put("collaterals", env.collaterals() == null ? 0 : env.collaterals().size());
        if (llmDrafted) {
            propDetail.put("llmDrafted", true);
            propDetail.put("llmModel", proposalDraft.model());
        }
        audit.ai("proposal-generator", "CREDIT_PROPOSAL_GENERATED", "Application", reference,
                "Generated credit proposal v%d (%s format, %d sections, grounded)"
                        .formatted(version, fmt.code(), p.getSections().size()),
                propDetail);
        return saved;
    }

    /** A rendered-only proposal — the same body {@link #generate} produces, but NOT persisted. */
    public record ProposalPreview(String applicationReference, String format, String label,
                                  List<String> sections, String markdown, String html,
                                  Map<String, Object> citations, boolean llmDrafted) {
    }

    /**
     * Render the credit proposal under a chosen CAM {@code format} WITHOUT persisting a
     * {@link CreditProposal} version — no DB write, no {@code CREDIT_PROPOSAL_GENERATED} audit.
     * It reuses the exact format-aware assembly of {@link #generate(String, String, String)} (the
     * same {@link #resolveFormat}, keyed section builders, inline-sublimits rule, grounding LLM
     * narrative and citations), so the rendered body matches what a real generate would produce —
     * only the persistence + version bump + audit stamp are skipped. Powers the Credit-Proposal
     * screen's side-by-side format compare so comparing formats never spams proposal versions.
     */
    @Transactional(readOnly = true)
    public ProposalPreview preview(String reference, String format) {
        DealEnvelopeDto env = upstream.envelope(reference);
        RiskSummaryDto rs = upstream.riskSummary(reference);
        if (rs == null || rs.rating() == null) {
            throw ApiException.conflict("Cannot preview proposal — the deal must be rated first");
        }
        List<Covenant> covs = covenants.findByApplicationReference(reference);
        CreditDecision dec = decisions.findFirstByApplicationReferenceOrderByCreatedAtDesc(reference).orElse(null);

        ResolvedFormat fmt = resolveFormat(format, env.segment());

        LlmResult proposalDraft = llmNarrative(env, rs, covs, dec);
        boolean llmDrafted = proposalDraft.usable();

        Md md = new Md();
        md.h1("Credit proposal · " + env.applicationReference());
        md.muted("Prepared by Helix · grounded in platform data · awaiting named human review and sign-off");
        md.spacer();
        if (llmDrafted) {
            md.h2("Executive narrative (AI-drafted, advisory)");
            md.line(proposalDraft.text().strip());
        }

        Ctx ctx = new Ctx(env, rs, covs, dec);
        Set<String> keys = new LinkedHashSet<>();
        for (Section s : fmt.sections()) keys.add(s.key());
        boolean inlineSublimits = !keys.contains("sublimits");
        for (Section s : fmt.sections()) {
            renderSection(s.key(), md, ctx, inlineSublimits);
        }
        // Fold human-CONFIRMED AI commentary INTO the proposal (no separate module).
        renderConfirmedCommentary(md, reference);

        Map<String, Object> citations = new LinkedHashMap<>();
        citations.put("envelope", "origination-service GET /api/applications/" + reference + "/envelope");
        citations.put("rating",   "risk-service GET /api/risk/" + reference + "/rating");
        citations.put("capital",  "risk-service GET /api/risk/" + reference + "/capital");
        citations.put("pricing",  "risk-service GET /api/risk/" + reference);
        citations.put("covenants","decision-service GET /api/decisions/" + reference + "/covenants");
        citations.put("decision", "decision-service GET /api/decisions/" + reference);

        // NO proposals.save(...), NO audit.ai(CREDIT_PROPOSAL_GENERATED) — render-only.
        return new ProposalPreview(reference, fmt.code(), fmt.label(),
                fmt.sections().stream().map(Section::title).toList(),
                md.markdown(), md.html(), citations, llmDrafted);
    }

    @Transactional(readOnly = true)
    public CreditProposal latest(String reference) {
        return proposals.findFirstByApplicationReferenceOrderByVersionDesc(reference)
                .orElseThrow(() -> ApiException.notFound("No credit proposal for " + reference));
    }

    @Transactional(readOnly = true)
    public List<CreditProposal> versions(String reference) {
        return proposals.findByApplicationReferenceOrderByVersionDesc(reference);
    }

    /**
     * Combined credit proposal across every member of a borrower group (PRD §8,
     * "combining credit proposal of group companies"). Stored as a normal
     * {@link CreditProposal} with {@code applicationReference = "GRP:" + groupRef}
     * so versioning/history reuse the existing repository, and the figures are
     * <em>quoted</em> from each member's upstream services — never invented and
     * never mutated. Stamped {@code audit.ai("proposal-generator", ...)}.
     */
    @Transactional
    public CreditProposal generateForGroup(String groupReference, String actor) {
        // The insights service already fetches group + exposure + per-member envelope/risk
        // and stamps audit.ai("GROUP_INSIGHTS_GENERATED"). Re-fetching them here doubles
        // the cross-service round-trips AND double-stamps the audit trail, so we read
        // everything we need off the insights record.
        GroupInsights insights = groupInsights.insights(groupReference, actor);

        Md md = new Md();
        md.h1("Combined credit proposal · " + nv(insights.groupName())
                + " (" + insights.groupReference() + ")");
        md.muted("Group-level rollup of every tagged member's authoritative figures. Advisory, grounded, "
                + "human-gated. No figure below is computed by the model — they are quoted verbatim from the "
                + "rating / capital / pricing / origination services.");
        md.spacer();

        // 1) Group summary
        md.h2("1. Group summary");
        md.bullets(
                bullet("Group reference", insights.groupReference()),
                bullet("Group RM", nv(insights.groupRm())),
                bullet("Country", nv(insights.country())),
                bullet("Multi-country", String.valueOf(insights.multiCountry())),
                bullet("Members tagged", String.valueOf(insights.memberCount())),
                bullet("Active obligors", String.valueOf(insights.obligorCount())),
                bullet("Members with live application", String.valueOf(insights.membersWithApplication())),
                bullet("Members below RAROC hurdle (advisory)", String.valueOf(insights.membersBelowHurdle())),
                bullet("Members with rating override", String.valueOf(insights.membersOverridden())));

        // 2) Group-level aggregates
        md.h2("2. Group-level aggregates (deterministic, unchanged)");
        Map<String, String> rollup = new LinkedHashMap<>();
        insights.totalExposureByCurrency().forEach((ccy, amt) ->
                rollup.put("Proposed exposure · " + ccy, String.format(Locale.UK, "%,.0f", amt)));
        if (insights.weightedAveragePd() != null) {
            rollup.put("Weighted-average PD", pct(insights.weightedAveragePd()));
        }
        if (insights.weightedAverageRaroc() != null) {
            rollup.put("Weighted-average RAROC", pct(insights.weightedAverageRaroc()));
        }
        if (insights.lowestGrade() != null) {
            rollup.put("Grade band (best → weakest)",
                    insights.highestGrade() + " → " + insights.lowestGrade());
        }
        if (insights.groupGrade() != null) {
            rollup.put("Group grade (advisory, derived)", insights.groupGrade());
            rollup.put("Group grade method", insights.groupGradeMethod());
        }
        md.kvBlock(rollup);

        // Section numbering is dynamic so we don't skip an integer when concentrations
        // or callouts are empty (a single-currency / single-segment book had been
        // rendering "2. → 4. → 5." with no "3.").
        int section = 3;
        if (!insights.concentrations().isEmpty()) {
            md.h2(section++ + ". Concentrations (advisory)");
            for (String c : insights.concentrations()) {
                md.line("- " + c);
            }
        }
        if (!insights.riskCallouts().isEmpty()) {
            md.h2(section++ + ". Risk callouts (advisory)");
            for (String c : insights.riskCallouts()) {
                md.line("- " + c);
            }
        }

        // Per-member sections — each pulls the member's deterministic figures verbatim.
        md.h2(section++ + ". Per-member breakdown");
        for (GroupMemberInsight m : insights.members()) {
            md.line("**" + m.counterpartyName() + "** · " + m.counterpartyRef()
                    + " · " + nv(m.segment()) + " · " + nv(m.recordType())
                    + " · RM " + nv(m.rm()));
            if (m.latestApplicationReference() == null) {
                md.line("_No live application on file._");
                continue;
            }
            Map<String, String> mv = new LinkedHashMap<>();
            mv.put("Latest application", m.latestApplicationReference()
                    + " · " + nv(m.applicationStatus()));
            if (m.finalGrade() != null) {
                mv.put("Rating (model → final)",
                        nv(m.modelGrade()) + " → " + m.finalGrade()
                                + (m.ratingConfirmed() ? " ✓" : " · unconfirmed")
                                + (m.ratingOverridden() ? " · OVERRIDDEN" : ""));
            }
            if (m.pd() != null) mv.put("PD", pct(m.pd()));
            if (m.exposure() != null) mv.put("Proposed exposure",
                    String.format(Locale.UK, "%,.0f", m.exposure()) + " " + nv(m.currency()));
            mv.put("Facilities", String.valueOf(m.facilityCount()));
            mv.put("Covenants", String.valueOf(m.covenantCount()));
            if (m.recommendedRate() != null) {
                mv.put("Pricing (advisory)",
                        "rate " + pct(m.recommendedRate())
                                + " · RAROC " + (m.raroc() == null ? "—" : pct(m.raroc()))
                                + " vs hurdle " + (m.hurdleRaroc() == null ? "—" : pct(m.hurdleRaroc()))
                                + (m.belowHurdle() ? " · BELOW HURDLE" : ""));
            }
            md.kvBlock(mv);
        }

        // Narrative
        md.h2(section++ + ". Advisory narrative");
        md.line(insights.narrative());

        // Provenance
        md.h2(section + ". Provenance and governance");
        md.bullets(
                bullet("Generated by", "Helix · proposal-generator (AI-assisted; human signs)"),
                bullet("Grounding",
                        "Every figure above is quoted verbatim from a platform service — none are invented or "
                                + "recomputed by this report"),
                bullet("Approval",
                        "AI cannot approve. A named human at the required group-level authority must sign each "
                                + "member proposal AND this combined rollup."),
                bullet("Member proposals",
                        "Linkages to per-member credit proposals remain authoritative; this rollup never "
                                + "supersedes them."));

        // Citations: one upstream lookup per member + the group endpoints.
        Map<String, Object> citations = new LinkedHashMap<>();
        citations.put("group",
                "counterparty-service GET /api/initiation/groups/by-reference/" + groupReference);
        citations.put("groupExposure",
                "counterparty-service GET /api/initiation/groups/by-reference/" + groupReference + "/exposure");
        Map<String, Object> memberCitations = new LinkedHashMap<>();
        for (GroupMemberInsight m : insights.members()) {
            memberCitations.put(m.counterpartyRef(),
                    "origination-service GET /api/applications/by-counterparty/" + m.counterpartyRef());
        }
        citations.put("members", memberCitations);

        String groupKey = "GRP:" + groupReference;
        int version = proposals.findFirstByApplicationReferenceOrderByVersionDesc(groupKey)
                .map(p -> p.getVersion() + 1).orElse(1);

        CreditProposal p = new CreditProposal();
        p.setApplicationReference(groupKey);
        p.setVersion(version);
        p.setMarkdown(md.markdown());
        p.setHtml(md.html());
        p.setCitations(citations);
        p.setSections(List.of(
                "Group summary", "Group-level aggregates", "Concentrations", "Risk callouts",
                "Per-member breakdown", "Advisory narrative", "Provenance"));
        // The combined group rollup has its own bespoke section set (not the per-deal CAM layout);
        // it is stamped STANDARD so the format column is populated without changing that layout.
        p.setFormat("STANDARD");
        p.setGeneratedBy(actor);
        CreditProposal saved = proposals.save(p);
        audit.ai("proposal-generator", "GROUP_CREDIT_PROPOSAL_GENERATED", "Group", insights.groupReference(),
                "Generated combined group proposal v%d for %s — %d member(s), %d with application"
                        .formatted(version, nv(insights.groupName()), insights.memberCount(), insights.membersWithApplication()),
                Map.of("version", version,
                        "memberCount", insights.memberCount(),
                        "membersWithApplication", insights.membersWithApplication(),
                        "membersBelowHurdle", insights.membersBelowHurdle(),
                        "advisory", true));
        return saved;
    }

    // ----------------------------------------------------------- section builders (keyed)

    /** Immutable bundle of the deal figures every section builder renders from. */
    private record Ctx(DealEnvelopeDto env, RiskSummaryDto rs, List<Covenant> covs, CreditDecision dec) {
    }

    /** Dispatch a stable section key to its builder. Unknown keys are skipped (forward-compatible). */
    /** Human-CONFIRMED AI commentary section labels (parallel to CommentaryService.SECTIONS). */
    private static final Map<String, String> COMMENTARY_TITLES = new LinkedHashMap<>() {{
        put("industry_outlook", "Industry outlook");
        put("management_quality", "Management quality");
        put("financial_commentary", "Financial commentary");
        put("structure_commentary", "Structure commentary");
        put("risk_commentary", "Risk commentary");
    }};

    /**
     * Weave any human-CONFIRMED AI commentary for this deal INTO the proposal document, so commentary
     * is part of the proposal itself (not a separate module). Only CONFIRMED rows flow in (DRAFT /
     * REJECTED ignored); the newest confirmed row per section wins. No figures are produced — the
     * narrative is advisory prose. When there is no confirmed commentary this appends nothing, so the
     * proposal is byte-identical to today.
     */
    private void renderConfirmedCommentary(Md md, String reference) {
        List<com.helix.decision.entity.ProposalCommentary> all =
                commentary.findByApplicationReferenceOrderByIdDesc(reference);
        if (all == null || all.isEmpty()) return;
        Map<String, com.helix.decision.entity.ProposalCommentary> bySection = new LinkedHashMap<>();
        for (com.helix.decision.entity.ProposalCommentary c : all) {
            if ("CONFIRMED".equals(c.getStatus())) bySection.putIfAbsent(c.getSection(), c);
        }
        if (bySection.isEmpty()) return;
        md.spacer();
        md.h2("AI commentary — human-confirmed");
        md.muted("Advisory narrative drafted by AI and confirmed by a named human (maker ≠ checker). "
                + "Figures are quoted from the deterministic record; this commentary introduces none.");
        for (String key : COMMENTARY_TITLES.keySet()) {
            var c = bySection.remove(key);
            if (c != null) appendCommentary(md, key, c);
        }
        for (var e : bySection.entrySet()) appendCommentary(md, e.getKey(), e.getValue());
    }

    private void appendCommentary(Md md, String key, com.helix.decision.entity.ProposalCommentary c) {
        md.line("**" + COMMENTARY_TITLES.getOrDefault(key, key) + "** — confirmed by "
                + (c.getReviewedBy() == null ? "reviewer" : c.getReviewedBy()));
        if (c.getNarrative() != null && !c.getNarrative().isBlank()) md.line(c.getNarrative().strip());
        List<String> bp = c.getBulletPoints();
        if (bp != null) {
            for (String b : bp) md.line("• " + b);
        }
    }

    private void renderSection(String key, Md md, Ctx ctx, boolean inlineSublimits) {
        switch (key) {
            case "executive_summary" -> secExecutiveSummary(md, ctx);
            case "facilities" -> secFacilities(md, ctx, inlineSublimits);
            case "sublimits" -> secSublimits(md, ctx);
            case "collateral" -> secCollateral(md, ctx);
            case "financials" -> secFinancials(md, ctx);
            case "ratios" -> secRatios(md, ctx);
            case "rating" -> secRating(md, ctx);
            case "capital" -> secCapital(md, ctx);
            case "pricing" -> secPricing(md, ctx);
            case "covenants" -> secCovenants(md, ctx);
            case "routing" -> secRouting(md, ctx);
            case "provenance" -> secProvenance(md, ctx);
            // segment-specific — render already-computed figures only (no new authoritative math)
            case "dscr_waterfall" -> secDscrWaterfall(md, ctx);
            case "rent_roll" -> secRentRoll(md, ctx);
            case "scf_program" -> secScfProgram(md, ctx);
            // richer bank-CAM sections — grounded where data exists, else advisory prose / graceful note.
            // None of these produce or mutate an authoritative figure; every figure is quoted verbatim.
            case "borrower_background" -> secBorrowerBackground(md, ctx);
            case "management" -> secBorrowerBackground(md, ctx);   // alias — company & management overview
            case "industry_outlook" -> secIndustryOutlook(md, ctx);
            case "financial_trend" -> secFinancialTrend(md, ctx);
            case "key_risks_mitigants" -> secKeyRisksMitigants(md, ctx);
            case "security_perfection" -> secSecurityPerfection(md, ctx);
            case "account_conduct" -> secAccountConduct(md, ctx);
            case "deviations" -> secDeviations(md, ctx);
            case "raroc_profitability" -> secRarocProfitability(md, ctx);
            case "esg" -> secEsg(md, ctx);
            case "conditions" -> secConditions(md, ctx);
            case "recommendation" -> secRecommendation(md, ctx);
            case "regulatory_compliance" -> secRegulatoryCompliance(md, ctx);
            default -> { /* unknown key — skip */ }
        }
    }

    private void secExecutiveSummary(Md md, Ctx ctx) {
        DealEnvelopeDto env = ctx.env();
        RiskSummaryDto rs = ctx.rs();
        md.h2("1. Executive summary");
        md.bullets(
                bullet("Borrower", env.counterpartyName()),
                bullet("Jurisdiction · segment", env.jurisdiction() + " · " + env.segment()),
                bullet("Proposed total exposure", money(env.totalProposedAmount(), env.currency())),
                bullet("Number of proposed facilities", String.valueOf(env.facilities() == null ? 0 : env.facilities().size())),
                bullet("Indicative tenor", env.tenorMonths() + " months"),
                bullet("Final grade (analyst proposed / approver confirmed)", rs.rating().finalGrade()
                        + (Boolean.TRUE.equals(rs.rating().confirmed()) ? " ✓" : " · unconfirmed")),
                bullet("RAROC (advisory)", rs.pricing() == null ? "—" :
                        pct(rs.pricing().raroc()) + " vs hurdle " + pct(rs.pricing().hurdleRaroc())
                                + (rs.pricing().belowHurdle() ? " · BELOW HURDLE" : "")));
    }

    private void secFacilities(Md md, Ctx ctx, boolean inlineSublimits) {
        DealEnvelopeDto env = ctx.env();
        md.h2("2. Facilities proposed");
        if (env.facilities() == null || env.facilities().isEmpty()) {
            md.line("_No facilities recorded._");
        } else {
            md.table(new String[]{"Ord", "Type", "Amount", "Tenor", "Purpose", "Indicative rate"});
            for (var f : env.facilities()) {
                md.row(String.valueOf(f.ordinal() + (f.primary() ? "★" : "")),
                        f.facilityType(), money(f.amount(), f.currency()),
                        f.tenorMonths() + "m", nv(f.purpose()),
                        f.indicativeRate() == null ? "—" : pct(f.indicativeRate()));
            }
        }
        if (inlineSublimits) secSublimits(md, ctx);
    }

    private void secSublimits(Md md, Ctx ctx) {
        DealEnvelopeDto env = ctx.env();
        // 2b) Sublimits and interchangeability per facility
        boolean anySublimits = env.facilities() != null && env.facilities().stream()
                .anyMatch(f -> f.sublimits() != null && !f.sublimits().isEmpty());
        if (anySublimits) {
            md.h2("2b. Sublimits and interchangeability");
            for (var f : env.facilities()) {
                if (f.sublimits() == null || f.sublimits().isEmpty()) continue;
                md.line("**" + f.facilityType() + " · " + money(f.amount(), f.currency()) + "** — sublimit total "
                        + money(f.sublimitTotal(), f.currency()) + " · headroom " + money(f.sublimitHeadroom(), f.currency()));
                md.table(new String[]{"Code", "Product", "Amount", "Tenor", "Interchangeable group", "Purpose"});
                for (var s : f.sublimits()) {
                    md.row(s.code(), s.productType(), money(s.amount(), s.currency()),
                            s.tenorMonths() == null ? "—" : s.tenorMonths() + "m",
                            s.interchangeableGroup() == null ? "fixed (hard cap)" : s.interchangeableGroup(),
                            nv(s.purpose()));
                }
                if (f.interchangeabilityGroups() != null && !f.interchangeabilityGroups().isEmpty()) {
                    md.line("Fungibility pools (utilisation may move freely within each):");
                    for (var g : f.interchangeabilityGroups()) {
                        md.line("- **" + g.groupKey() + "** · combined cap "
                                + money(g.combinedCap(), g.currency()) + " · members: "
                                + String.join(", ", g.memberCodes()));
                    }
                }
            }
        }
    }

    private void secCollateral(Md md, Ctx ctx) {
        DealEnvelopeDto env = ctx.env();
        md.h2("3. Collateral and security");
        if (env.collaterals() == null || env.collaterals().isEmpty()) {
            md.line("_Unsecured / no collateral recorded._");
        } else {
            md.table(new String[]{"Type", "Description", "Market value", "Haircut", "Effective", "Perfection"});
            for (var c : env.collaterals()) {
                md.row(c.collateralType(), nv(c.description()), money(c.marketValue(), env.currency()),
                        pct(c.haircut()), money(c.effectiveValue(), env.currency()), c.perfectionStatus());
            }
            md.line("**Total effective coverage:** " + money(env.totalCollateralCover(), env.currency()));
        }
    }

    private void secFinancials(Md md, Ctx ctx) {
        DealEnvelopeDto env = ctx.env();
        md.h2("4. Financial position (latest period)");
        md.kvBlock(format(env.latestFinancials(), Map.of(
                "REVENUE", "Revenue", "EBITDA", "EBITDA", "PAT", "PAT", "TOTAL_DEBT", "Total debt",
                "CASH", "Cash", "NET_WORTH", "Net worth", "CFO", "Cash flow from ops")));
    }

    private void secRatios(Md md, Ctx ctx) {
        DealEnvelopeDto env = ctx.env();
        md.h2("Ratios");
        md.kvBlock(formatRatios(env.ratios(), Map.of(
                "NET_LEVERAGE", "Net leverage", "INTEREST_COVERAGE", "Interest coverage",
                "DSCR", "DSCR", "EBITDA_MARGIN", "EBITDA margin", "CURRENT_RATIO", "Current ratio",
                "GEARING", "Gearing", "RETURN_ON_EQUITY", "Return on equity")));
    }

    private void secRating(Md md, Ctx ctx) {
        RiskSummaryDto rs = ctx.rs();
        md.h2("5. Risk rating");
        md.line("Model proposed **" + rs.rating().modelGrade() + "** · final **" + rs.rating().finalGrade()
                + "** · PD " + pct(rs.rating().pd()));
        if (rs.rating().overridden()) {
            md.line("> **Override applied** — escalation: " + rs.rating().escalated());
        }
    }

    private void secCapital(Md md, Ctx ctx) {
        DealEnvelopeDto env = ctx.env();
        RiskSummaryDto rs = ctx.rs();
        md.h2("6. Capital projection (for RAROC)");
        md.line("_Note: the bank's capital engine remains the system of record. This figure is an internal projection used by the pricing engine only._");
        if (rs.capital() != null) {
            md.kvBlock(Map.of(
                    "Exposure class", rs.capital().exposureClass(),
                    "RWA (projected)", money(rs.capital().rwa(), env.currency()),
                    "Capital projection", money(rs.capital().capitalRequired(), env.currency())));
        }
    }

    private void secPricing(Md md, Ctx ctx) {
        RiskSummaryDto rs = ctx.rs();
        md.h2("7. Risk-adjusted pricing (advisory)");
        if (rs.pricing() != null) {
            md.kvBlock(Map.of(
                    "Recommended rate", pct(rs.pricing().recommendedRate()),
                    "RAROC", pct(rs.pricing().raroc()),
                    "Hurdle", pct(rs.pricing().hurdleRaroc()),
                    "Status", rs.pricing().belowHurdle() ? "Below hurdle — escalate" : "Clears hurdle"));
        }
    }

    private void secCovenants(Md md, Ctx ctx) {
        List<Covenant> covs = ctx.covs();
        md.h2("8. Covenants");
        if (covs.isEmpty()) {
            md.line("_No covenants set._");
        } else {
            md.table(new String[]{"Type", "Metric", "Test", "Frequency", "Severity"});
            for (Covenant c : covs) {
                md.row(c.getCovenantType(), c.getMetric(), c.getOperator() + " " + c.getThreshold(),
                        c.getTestFrequency(), c.getBreachSeverity());
            }
        }
    }

    private void secRouting(Md md, Ctx ctx) {
        CreditDecision dec = ctx.dec();
        md.h2("9. Routing & decision");
        if (dec != null) {
            md.line("Routed to **" + dec.getRequiredAuthority() + "** authority. Status: " + dec.getStatus()
                    + (dec.getOutcome() == null ? "" : " · " + dec.getOutcome() + " by " + dec.getDecidedBy()));
            if (dec.getDeviations() != null && !dec.getDeviations().isEmpty()) {
                md.line("Deviations:");
                dec.getDeviations().forEach(d -> md.line("- " + d));
            }
            if (dec.getConditions() != null && !dec.getConditions().isEmpty()) {
                md.line("Conditions:");
                dec.getConditions().forEach(d -> md.line("- " + d));
            }
        } else {
            md.line("_Not yet routed for approval._");
        }
    }

    private void secProvenance(Md md, Ctx ctx) {
        md.h2("10. Provenance and governance");
        md.bullets(
                bullet("Generated by", "Helix · proposal-generator (AI-assisted, human signs)"),
                bullet("Grounding", "Every figure above is quoted from a platform service; no figure is invented by the model"),
                bullet("Approval", "AI cannot approve. A named human at the required authority must sign."));
    }

    // ---- segment-specific builders — render EXISTING figures only, graceful when absent ----

    private void secDscrWaterfall(Md md, Ctx ctx) {
        DealEnvelopeDto env = ctx.env();
        md.h2("DSCR & cashflow waterfall (advisory)");
        md.line("_Coverage figures quoted from the deal's spread — no figure is recomputed here._");
        Map<String, Double> ratios = env.ratios();
        Map<String, Double> fin = env.latestFinancials();
        Map<String, String> kv = new LinkedHashMap<>();
        putRatio(kv, ratios, "DSCR", "DSCR (x)");
        putRatio(kv, ratios, "INTEREST_COVERAGE", "Interest coverage (x)");
        putMoney(kv, fin, "CFO", "Cash flow from ops", env.currency());
        putMoney(kv, fin, "TOTAL_DEBT", "Total debt", env.currency());
        if (kv.isEmpty()) md.line("_No DSCR / cashflow data captured for this deal — not applicable._");
        else md.kvBlock(kv);
    }

    private void secRentRoll(Md md, Ctx ctx) {
        DealEnvelopeDto env = ctx.env();
        md.h2("Rent roll & lease profile (advisory)");
        md.line("_Lease-rental figures quoted from the deal's spread — no figure is recomputed here._");
        Map<String, Double> ratios = env.ratios();
        Map<String, String> kv = new LinkedHashMap<>();
        putRatio(kv, ratios, "CAM_RENTAL_DSCR", "Rental cover (x of debt service)");
        putRatio(kv, ratios, "CAM_LTV", "Loan-to-value (x)");
        putRatio(kv, ratios, "DSCR", "DSCR (x)");
        if (kv.isEmpty()) md.line("_No lease-rental data captured for this deal — not applicable._");
        else md.kvBlock(kv);
    }

    private void secScfProgram(Md md, Ctx ctx) {
        DealEnvelopeDto env = ctx.env();
        md.h2("Supply-chain-finance programme (advisory)");
        md.line("_Programme figures quoted from the deal's spread — no figure is recomputed here._");
        Map<String, Double> ratios = env.ratios();
        Map<String, String> kv = new LinkedHashMap<>();
        putRatio(kv, ratios, "ANCHOR_DEPENDENCE", "Anchor dependence (x)");
        putRatio(kv, ratios, "DILUTION_RATE", "Invoice dilution rate (x)");
        putRatio(kv, ratios, "RECEIVABLE_DAYS", "Anchor receivable days");
        if (kv.isEmpty()) md.line("_No supply-chain-finance programme data captured for this deal — not applicable._");
        else md.kvBlock(kv);
    }

    private static void putRatio(Map<String, String> kv, Map<String, Double> ratios, String key, String label) {
        Double v = ratios == null ? null : ratios.get(key);
        if (v != null) kv.put(label, String.format(Locale.UK, "%.2f", v));
    }

    private static void putMoney(Map<String, String> kv, Map<String, Double> vals, String key, String label, String ccy) {
        Double v = vals == null ? null : vals.get(key);
        if (v != null) kv.put(label, money(v, ccy));
    }

    // ---- richer bank-CAM section builders -------------------------------------------------------
    // Each is GROUNDED where the data exists (envelope / risk summary / local CAD·CP·perfection·
    // annexure records) and degrades to an ADVISORY prose narrative or a short "not available" line
    // otherwise. NONE mutates or produces an authoritative figure — every figure is quoted verbatim,
    // and the advisory narrative is prose-only and fail-soft (no provider ⇒ deterministic content).

    /** Company & management overview. Also aliased from the {@code management} key. */
    private void secBorrowerBackground(Md md, Ctx ctx) {
        DealEnvelopeDto env = ctx.env();
        md.h2("Borrower background & management (advisory)");
        md.bullets(
                bullet("Borrower", nv(env.counterpartyName())),
                bullet("Jurisdiction · segment", nv(env.jurisdiction()) + " · " + nv(env.segment())),
                bullet("Proposed total exposure", money(env.totalProposedAmount(), env.currency())),
                bullet("Facility lines", String.valueOf(env.facilities() == null ? 0 : env.facilities().size())),
                bullet("Indicative tenor", env.tenorMonths() + " months"));
        LlmResult n = advisoryNarrative("proposal-borrower-background",
                "Summarise the borrower's business profile and management standing at a high level.",
                "Borrower: " + nv(env.counterpartyName()) + "; jurisdiction/segment: " + nv(env.jurisdiction())
                        + " / " + nv(env.segment()) + "; proposed exposure "
                        + money(env.totalProposedAmount(), env.currency()) + " over " + env.tenorMonths() + " months.");
        if (n.usable()) md.line(n.text().strip());
        else md.muted("Company & management narrative to be completed by the RM (no AI narrative provider configured).");
    }

    /** Sector view — grounded from a SECTOR_OUTLOOK master when seeded, else advisory prose. */
    private void secIndustryOutlook(Md md, Ctx ctx) {
        DealEnvelopeDto env = ctx.env();
        md.h2("Industry outlook (advisory)");
        Map<String, Object> outlook = sectorOutlook(env.segment());
        if (outlook != null && !outlook.isEmpty()) {
            md.muted("Sourced from the SECTOR_OUTLOOK master (config-as-data) for segment " + nv(env.segment()) + ".");
            Map<String, String> kv = new LinkedHashMap<>();
            for (String k : List.of("stance", "outlook", "summary", "headwinds", "tailwinds", "view")) {
                Object v = outlook.get(k);
                if (v != null && !String.valueOf(v).isBlank()) kv.put(cap(k), String.valueOf(v));
            }
            if (!kv.isEmpty()) { md.kvBlock(kv); return; }
        }
        LlmResult n = advisoryNarrative("proposal-industry-outlook",
                "Give a brief, balanced sector view (demand-supply, regulation, key headwinds/tailwinds) for the borrower's segment.",
                "Segment: " + nv(env.segment()) + "; jurisdiction: " + nv(env.jurisdiction()) + ".");
        if (n.usable()) md.line(n.text().strip());
        else md.muted("Sector outlook not available — no SECTOR_OUTLOOK master seeded and no AI narrative provider configured. Attach the industry-scenario annexure manually.");
    }

    /** MULTI-YEAR spread trend. Renders a period-over-period table when the spread has &gt;1 period. */
    private void secFinancialTrend(Md md, Ctx ctx) {
        DealEnvelopeDto env = ctx.env();
        md.h2("Financial trend (multi-year)");
        md.line("_Figures quoted verbatim from the deal's confirmed spread — no figure is recomputed here._");
        List<PeriodFinancialsDto> ps = env.periodFinancials();
        if (ps == null || ps.isEmpty()) {
            Map<String, String> latest = format(env.latestFinancials(), TREND_LINES);
            if (latest.isEmpty()) md.line("_No spread on record — financial trend not available._");
            else { md.muted("Single-period snapshot only (no multi-period spread captured)."); md.kvBlock(latest); }
            return;
        }
        List<String> metrics = new ArrayList<>();
        for (var e : TREND_LINES.entrySet()) {
            boolean any = ps.stream().anyMatch(p -> p.values() != null && p.values().get(e.getKey()) != null);
            if (any) metrics.add(e.getKey());
        }
        if (metrics.isEmpty()) { md.line("_Spread present but no comparable line items across periods._"); return; }
        String[] header = new String[ps.size() + 1];
        header[0] = "Metric";
        for (int i = 0; i < ps.size(); i++) {
            PeriodFinancialsDto p = ps.get(i);
            header[i + 1] = (p.label() == null ? "P" + i : p.label())
                    + (p.currency() == null ? "" : " (" + p.currency() + ")");
        }
        md.table(header);
        for (String key : metrics) {
            String[] cells = new String[ps.size() + 1];
            cells[0] = TREND_LINES.get(key);
            for (int i = 0; i < ps.size(); i++) {
                Double v = ps.get(i).values() == null ? null : ps.get(i).values().get(key);
                cells[i + 1] = v == null ? "—" : String.format(Locale.UK, "%,.0f", v);
            }
            md.row(cells);
        }
        if (ps.size() == 1) md.muted("Only one period on record — add prior-year comparatives to the spread for a fuller trend.");
    }

    /** Key risks (derived from the authoritative figures — quoted, never recomputed) + advisory mitigants. */
    private void secKeyRisksMitigants(Md md, Ctx ctx) {
        DealEnvelopeDto env = ctx.env();
        RiskSummaryDto rs = ctx.rs();
        md.h2("Key risks & mitigants (advisory)");
        List<String> risks = new ArrayList<>();
        if (rs.pricing() != null && rs.pricing().belowHurdle()) {
            risks.add("Return below hurdle — RAROC " + pct(rs.pricing().raroc()) + " vs hurdle "
                    + pct(rs.pricing().hurdleRaroc()) + " (escalation required).");
        }
        if (rs.rating() != null && rs.rating().overridden()) {
            risks.add("Rating override applied — model " + nv(rs.rating().modelGrade()) + " → final "
                    + nv(rs.rating().finalGrade()) + "; ensure the override rationale is on file.");
        }
        Map<String, Double> ratios = env.ratios();
        Double dscr = ratios == null ? null : ratios.get("DSCR");
        if (dscr != null && dscr < 1.25) risks.add("Thin debt-service cover — DSCR " + String.format(Locale.UK, "%.2f", dscr) + "x (< 1.25x).");
        Double lev = ratios == null ? null : ratios.get("NET_LEVERAGE");
        if (lev != null && lev > 4.0) risks.add("Elevated leverage — net leverage " + String.format(Locale.UK, "%.2f", lev) + "x (> 4.0x).");
        Double icr = ratios == null ? null : ratios.get("INTEREST_COVERAGE");
        if (icr != null && icr < 2.0) risks.add("Weak interest cover — " + String.format(Locale.UK, "%.2f", icr) + "x (< 2.0x).");
        if (env.collaterals() == null || env.collaterals().isEmpty()) risks.add("Unsecured — no collateral recorded against the exposure.");
        if (risks.isEmpty()) {
            md.line("_No material risk flags derived from the deterministic figures. Complete the qualitative risk assessment manually._");
        } else {
            md.line("Risk flags derived from the authoritative figures (advisory — quoted, not recomputed):");
            for (String r : risks) md.line("- " + r);
        }
        LlmResult n = advisoryNarrative("proposal-key-risks",
                "Given the derived risk flags, suggest standard mitigants (covenants, security, monitoring). Do not add new risks or figures.",
                "Risk flags: " + (risks.isEmpty() ? "none material" : String.join("; ", risks)) + ".");
        if (n.usable()) { md.line("**Mitigants (advisory):**"); md.line(n.text().strip()); }
    }

    /** Perfection / CERSAI detail — envelope collateral perfection status + local perfection cases/steps. */
    private void secSecurityPerfection(Md md, Ctx ctx) {
        DealEnvelopeDto env = ctx.env();
        md.h2("Security & perfection status");
        boolean any = false;
        if (env.collaterals() != null && !env.collaterals().isEmpty()) {
            any = true;
            md.table(new String[]{"Type", "Description", "Effective value", "Perfection"});
            for (var c : env.collaterals()) {
                md.row(c.collateralType(), nv(c.description()), money(c.effectiveValue(), env.currency()),
                        nv(c.perfectionStatus()));
            }
        }
        for (PerfectionCase pc : perfectionCases.findByApplicationRefOrderByIdDesc(env.applicationReference())) {
            any = true;
            md.line("**Perfection case " + pc.getPerfRef() + "** · " + nv(pc.getSubjectType()) + " "
                    + nv(pc.getSubjectRef()) + " · status " + nv(pc.getStatus()));
            List<PerfectionStep> steps = perfectionSteps.findByCaseRefOrderByStepOrderAsc(pc.getPerfRef());
            if (!steps.isEmpty()) {
                md.table(new String[]{"Step", "Owner role", "Status"});
                for (PerfectionStep s : steps) md.row(nv(s.getTitle()), nv(s.getOwnerRole()), nv(s.getStatus()));
            }
        }
        if (!any) md.line("_Unsecured / no collateral or perfection case on record._");
    }

    /** Banking account-conduct summary — sourced from a feed not wired here; graceful placeholder. */
    private void secAccountConduct(Md md, Ctx ctx) {
        md.h2("Account conduct (banking summary)");
        md.muted("Account-conduct / ASR (account-statement-review) data is sourced from the core-banking conduct feed, "
                + "which is not wired into this proposal build. Attach the banking-conduct summary manually — no figure is asserted here.");
    }

    /** Deviations & justifications — CAD waivers/deviations + any routing deviations. */
    private void secDeviations(Md md, Ctx ctx) {
        DealEnvelopeDto env = ctx.env();
        CreditDecision dec = ctx.dec();
        md.h2("Deviations & justifications");
        boolean any = false;
        Optional<CadCase> cad = cadCases.findFirstByApplicationRefOrderByIdDesc(env.applicationReference());
        if (cad.isPresent()) {
            List<Deviation> devs = deviations.findByCadCaseIdOrderByCreatedAtDesc(cad.get().getId());
            if (!devs.isEmpty()) {
                any = true;
                md.table(new String[]{"Type", "Checklist item", "Justification", "Status", "L1", "L2"});
                for (Deviation d : devs) {
                    String item = checklistItems.findById(d.getChecklistItemId())
                            .map(ChecklistItem::getDescription).orElse("#" + d.getChecklistItemId());
                    md.row(nv(d.getType()), item, nv(d.getReason()), nv(d.getStatus()),
                            nv(d.getApproverL1()), nv(d.getApproverL2()));
                }
            }
        }
        if (dec != null && dec.getDeviations() != null && !dec.getDeviations().isEmpty()) {
            any = true;
            md.line("Routing deviations recorded at decision:");
            for (String s : dec.getDeviations()) md.line("- " + s);
        }
        if (!any) md.line("_No deviations or waivers recorded._");
    }

    /** RAROC + profitability posture, quoted verbatim from the pricing engine. */
    private void secRarocProfitability(Md md, Ctx ctx) {
        RiskSummaryDto rs = ctx.rs();
        md.h2("RAROC & profitability (advisory)");
        if (rs.pricing() == null) { md.line("_Pricing not yet run — RAROC unavailable._"); return; }
        Map<String, String> kv = new LinkedHashMap<>();
        kv.put("Recommended rate", pct(rs.pricing().recommendedRate()));
        kv.put("RAROC", pct(rs.pricing().raroc()));
        kv.put("Hurdle RAROC", pct(rs.pricing().hurdleRaroc()));
        kv.put("Status", rs.pricing().belowHurdle() ? "Below hurdle — escalate" : "Clears hurdle");
        md.kvBlock(kv);
        md.muted("Ancillary / fee income is not separately captured in the pricing-engine feed; the RAROC above is the "
                + "risk-adjusted return on the funded exposure, quoted verbatim from risk-service.");
    }

    /** ESG assessment — reuses an attached ESG annexure if present, else advisory prose. */
    private void secEsg(Md md, Ctx ctx) {
        DealEnvelopeDto env = ctx.env();
        md.h2("ESG assessment (advisory)");
        Annexure esg = null;
        for (Annexure a : annexures.findBySubjectRefOrderByIdDesc(env.applicationReference())) {
            if ("ESG_ASSESSMENT".equalsIgnoreCase(a.getAnnexureType())) { esg = a; break; }
        }
        if (esg != null) {
            md.muted("Sourced from ESG annexure " + esg.getAnnexureRef() + " · status " + esg.getStatus()
                    + (esg.isAdvisory() ? " · advisory" : "") + ".");
            Map<String, Object> sections = esg.getSections();
            if (sections != null && !sections.isEmpty()) {
                Map<String, String> kv = new LinkedHashMap<>();
                sections.forEach((k, v) -> {
                    if (v != null && !String.valueOf(v).isBlank()) kv.put(cap(k), String.valueOf(v));
                });
                if (!kv.isEmpty()) { md.kvBlock(kv); return; }
            }
            md.line("_ESG annexure attached but not yet authored._");
            return;
        }
        LlmResult n = advisoryNarrative("proposal-esg",
                "Give a brief environmental / social / governance risk view for the borrower's segment.",
                "Borrower: " + nv(env.counterpartyName()) + "; segment: " + nv(env.segment())
                        + "; jurisdiction: " + nv(env.jurisdiction()) + ".");
        if (n.usable()) md.line(n.text().strip());
        else md.muted("ESG assessment not available — no ESG annexure attached and no AI narrative provider configured.");
    }

    /** Conditions precedent / subsequent — the CP register + any conditions attached at decision. */
    private void secConditions(Md md, Ctx ctx) {
        DealEnvelopeDto env = ctx.env();
        CreditDecision dec = ctx.dec();
        md.h2("Conditions precedent & subsequent");
        boolean any = false;
        List<ConditionPrecedent> cps = conditionPrecedents.findByApplicationReferenceOrderByIdAsc(env.applicationReference());
        if (!cps.isEmpty()) {
            any = true;
            md.table(new String[]{"Code", "Condition", "Facility", "Mandatory", "Status"});
            for (ConditionPrecedent cp : cps) {
                md.row(nv(cp.getCode()), nv(cp.getTitle()), nv(cp.getFacilityRef()),
                        cp.isMandatory() ? "Yes" : "No", nv(cp.getStatus()));
            }
        }
        if (dec != null && dec.getConditions() != null && !dec.getConditions().isEmpty()) {
            any = true;
            md.line("Conditions attached at decision:");
            for (String s : dec.getConditions()) md.line("- " + s);
        }
        if (!any) md.line("_No conditions precedent or subsequent registered._");
    }

    /** RM recommendation — reports the named-human decision (never substitutes an AI decision). */
    private void secRecommendation(Md md, Ctx ctx) {
        CreditDecision dec = ctx.dec();
        md.h2("Recommendation");
        if (dec == null) { md.line("_Pending routing and the named-human recommendation._"); return; }
        if (dec.getOutcome() != null) {
            md.line("Decision: **" + dec.getOutcome() + "** by " + nv(dec.getDecidedBy())
                    + (dec.getDeciderRole() == null ? "" : " (" + dec.getDeciderRole() + ")") + ".");
            if (dec.getRationale() != null && !dec.getRationale().isBlank()) md.line("Rationale: " + dec.getRationale());
        } else {
            md.line("Routed to **" + nv(dec.getRequiredAuthority()) + "** — awaiting the named-human decision.");
        }
        md.muted("The recommendation and sign-off are a named-human action; this section reports it and never substitutes for it.");
    }

    /** Exposure-norm / limit compliance posture (deterministically enforced in limit-service). */
    private void secRegulatoryCompliance(Md md, Ctx ctx) {
        DealEnvelopeDto env = ctx.env();
        RiskSummaryDto rs = ctx.rs();
        md.h2("Regulatory & exposure-norm compliance");
        Map<String, String> kv = new LinkedHashMap<>();
        kv.put("Jurisdiction", nv(env.jurisdiction()));
        kv.put("Proposed exposure", money(env.totalProposedAmount(), env.currency()));
        kv.put("Tenor", env.tenorMonths() + " months");
        if (rs.rating() != null) kv.put("Rating escalation", rs.rating().escalated() ? "Escalated" : "None");
        if (rs.pricing() != null) kv.put("Below-hurdle escalation", rs.pricing().belowHurdle() ? "Required" : "Not triggered");
        md.kvBlock(kv);
        md.muted("Single / group-borrower exposure norms and country / department limits are enforced deterministically in "
                + "limit-service at booking. This section summarises the governance posture; it does not recompute any limit.");
    }

    // ---- helpers for the richer sections --------------------------------------------------------

    /** Canonical trend lines rendered in the multi-year table, in display order. */
    private static final Map<String, String> TREND_LINES = trendLines();

    private static Map<String, String> trendLines() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("REVENUE", "Revenue");
        m.put("EBITDA", "EBITDA");
        m.put("PAT", "PAT");
        m.put("TOTAL_DEBT", "Total debt");
        m.put("NET_WORTH", "Net worth");
        m.put("CASH", "Cash");
        m.put("CFO", "Cash flow from ops");
        return m;
    }

    /**
     * Advisory, prose-only narrative helper for the qualitative sections. Never produces or mutates a
     * figure and never recommends a credit decision. Fail-soft: {@code none}/error/empty returns an
     * unusable {@link LlmResult}, so the caller falls back to grounded facts or a graceful note.
     */
    private LlmResult advisoryNarrative(String capability, String guidance, String facts) {
        String system = "You are drafting an ADVISORY, prose-only section for a wholesale-credit appraisal memo "
                + "(capability=" + capability + "). " + guidance + " Use ONLY the facts supplied; quote every figure "
                + "verbatim and never invent, estimate or change a value. Do not approve, reject or recommend a credit "
                + "decision — a named human at the required authority signs. Reply with 3-5 sentences of plain prose.";
        return safeComplete(LlmRequest.of(capability, system, facts));
    }

    /** The SECTOR_OUTLOOK master matching the deal segment (by record key or payload segment), or null. */
    private Map<String, Object> sectorOutlook(String segment) {
        if (segment == null || segment.isBlank()) return null;
        for (MasterRecordDto r : upstream.masters("SECTOR_OUTLOOK")) {
            Map<String, Object> p = payload(r);
            Object seg = p.get("segment");
            if (segment.equalsIgnoreCase(r.recordKey())
                    || (seg != null && segment.equalsIgnoreCase(String.valueOf(seg)))) {
                return p;
            }
        }
        return null;
    }

    private static String cap(String key) {
        if (key == null || key.isBlank()) return key;
        String s = key.replace('_', ' ').trim().toLowerCase(Locale.UK);
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    // ----------------------------------------------------------- format resolution

    /** A section in a resolved CAM format: a stable builder {@code key} + its human {@code title}. */
    public record Section(String key, String title) {
    }

    /** A resolved CAM proposal format: code, label, defaulting segment, and its ordered sections. */
    public record ResolvedFormat(String code, String label, String segment, List<Section> sections) {
    }

    /** A listing row for the format picker (adds {@code recommended} for a given segment). */
    public record ProposalFormatView(String code, String label, String segment,
                                     List<Section> sections, boolean recommended) {
    }

    /**
     * Resolve the format to assemble under. Precedence: explicit {@code code} (matched against a
     * PROPOSAL_FORMAT master, else STANDARD) → the deal {@code segment}'s default (most-specific
     * PROPOSAL_FORMAT whose {@code segment} matches) → STANDARD. Never throws — a config-service
     * outage falls back to the built-in STANDARD layout (byte-identical to the universal proposal).
     */
    private ResolvedFormat resolveFormat(String code, String segment) {
        List<MasterRecordDto> recs = upstream.masters("PROPOSAL_FORMAT");
        if (code != null && !code.isBlank()) {
            for (MasterRecordDto r : recs) {
                if (code.equalsIgnoreCase(r.recordKey())) return toResolved(r);
            }
            // Explicit-but-unknown code (or explicit STANDARD without a seed): fall back to STANDARD.
            return standardOrBuiltin(recs);
        }
        if (segment != null && !segment.isBlank()) {
            MasterRecordDto best = null;
            for (MasterRecordDto r : recs) {
                Object seg = payload(r).get("segment");
                if (seg != null && segment.equalsIgnoreCase(String.valueOf(seg))) {
                    best = r;   // unique per segment in the seed; last-wins is deterministic on a stable list
                }
            }
            if (best != null) return toResolved(best);
        }
        return standardOrBuiltin(recs);
    }

    private ResolvedFormat standardOrBuiltin(List<MasterRecordDto> recs) {
        for (MasterRecordDto r : recs) {
            if ("STANDARD".equalsIgnoreCase(r.recordKey())) {
                ResolvedFormat f = toResolved(r);
                if (!f.sections().isEmpty()) return f;
            }
        }
        return builtinStandard();
    }

    /**
     * The built-in STANDARD layout — the universal proposal's sections, in the universal order,
     * with the exact human titles previously persisted. Used only when config-service is unreachable
     * or has no STANDARD record; the seeded STANDARD master mirrors it, so the output is identical
     * either way.
     */
    private static ResolvedFormat builtinStandard() {
        return new ResolvedFormat("STANDARD", "Standard universal CAM", null, List.of(
                new Section("executive_summary", "Executive summary"),
                new Section("facilities", "Facilities proposed"),
                new Section("collateral", "Collateral and security"),
                new Section("financials", "Financials"),
                new Section("ratios", "Ratios"),
                new Section("rating", "Rating"),
                new Section("capital", "Capital projection"),
                new Section("pricing", "Pricing"),
                new Section("covenants", "Covenants"),
                new Section("routing", "Routing & decision"),
                new Section("provenance", "Provenance")));
    }

    @SuppressWarnings("unchecked")
    private ResolvedFormat toResolved(MasterRecordDto r) {
        Map<String, Object> p = payload(r);
        String label = str(p.get("label"), r.recordKey());
        String segment = str(p.get("segment"), null);
        List<Section> secs = new ArrayList<>();
        Object raw = p.get("sections");
        if (raw instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof Map<?, ?> m) {
                    Object k = m.get("key");
                    if (k == null || String.valueOf(k).isBlank()) continue;
                    String key = String.valueOf(k);
                    String title = m.get("title") == null ? key : String.valueOf(m.get("title"));
                    secs.add(new Section(key, title));
                }
            }
        }
        if (secs.isEmpty()) secs = builtinStandard().sections();
        return new ResolvedFormat(r.recordKey(), label, segment, secs);
    }

    /**
     * The available proposal formats for the picker (read-only). Always includes STANDARD; when a
     * {@code segment} is supplied the segment default is flagged {@code recommended} and sorted first.
     */
    @Transactional(readOnly = true)
    public List<ProposalFormatView> proposalFormats(String segment) {
        List<MasterRecordDto> recs = upstream.masters("PROPOSAL_FORMAT");
        List<ResolvedFormat> formats = new ArrayList<>();
        boolean hasStandard = false;
        for (MasterRecordDto r : recs) {
            formats.add(toResolved(r));
            if ("STANDARD".equalsIgnoreCase(r.recordKey())) hasStandard = true;
        }
        if (!hasStandard) formats.add(0, builtinStandard());
        String recommended = resolveFormat(null, segment).code();
        List<ProposalFormatView> views = new ArrayList<>();
        for (ResolvedFormat f : formats) {
            views.add(new ProposalFormatView(f.code(), f.label(), f.segment(), f.sections(),
                    f.code().equalsIgnoreCase(recommended)));
        }
        // Recommended first (when a segment was given), then STANDARD, then alphabetical by code.
        views.sort((a, b) -> {
            if (a.recommended() != b.recommended()) return a.recommended() ? -1 : 1;
            boolean as = "STANDARD".equalsIgnoreCase(a.code());
            boolean bs = "STANDARD".equalsIgnoreCase(b.code());
            if (as != bs) return as ? -1 : 1;
            return a.code().compareToIgnoreCase(b.code());
        });
        return views;
    }

    private static Map<String, Object> payload(MasterRecordDto r) {
        return r.payload() == null ? Map.of() : r.payload();
    }

    private static String str(Object o, String dflt) {
        if (o == null) return dflt;
        String s = String.valueOf(o);
        return s.isBlank() ? dflt : s;
    }

    // ----------------------------------------------------------- advisory LLM narrative

    /**
     * Advisory LLM narrative for the per-deal proposal, grounded strictly in the deterministic
     * figures the report renders (amount, grade, PD, pricing, ratios, routing). The model is told
     * to quote every figure verbatim and invent nothing, and never to recommend a decision. Returns
     * the {@link LlmResult} so the caller keeps the deterministic render on {@code none}/failure/empty.
     */
    private LlmResult llmNarrative(DealEnvelopeDto env, RiskSummaryDto rs, List<Covenant> covs, CreditDecision dec) {
        String system = "You are drafting an ADVISORY executive narrative for a wholesale-credit proposal "
                + "(capability=proposal-draft). Write grounded, professional prose using ONLY the facts supplied. "
                + "Quote every figure (amount, grade, PD, rate, RAROC, hurdle, ratios) exactly as given and never "
                + "invent, estimate or change a value. Do not approve, reject or recommend a decision; a named human "
                + "at the required authority signs. Reply with 3-5 sentences of plain prose.";
        StringBuilder user = new StringBuilder();
        user.append("Borrower: ").append(nv(env.counterpartyName())).append("; jurisdiction/segment: ")
                .append(nv(env.jurisdiction())).append(" / ").append(nv(env.segment())).append(".\n");
        user.append("Proposed total exposure: ").append(money(env.totalProposedAmount(), env.currency()))
                .append(" over ").append(env.tenorMonths()).append(" months across ")
                .append(env.facilities() == null ? 0 : env.facilities().size()).append(" facility line(s).\n");
        if (rs.rating() != null) {
            user.append("Rating: model ").append(nv(rs.rating().modelGrade())).append(", final ")
                    .append(nv(rs.rating().finalGrade())).append(", PD ").append(pct(rs.rating().pd()))
                    .append(rs.rating().overridden() ? " (override applied)" : "").append(".\n");
        }
        if (rs.capital() != null) {
            user.append("Capital projection: exposure class ").append(nv(rs.capital().exposureClass()))
                    .append(", RWA ").append(money(rs.capital().rwa(), env.currency())).append(".\n");
        }
        if (rs.pricing() != null) {
            user.append("Pricing (advisory): rate ").append(pct(rs.pricing().recommendedRate()))
                    .append(", RAROC ").append(pct(rs.pricing().raroc())).append(" vs hurdle ")
                    .append(pct(rs.pricing().hurdleRaroc()))
                    .append(rs.pricing().belowHurdle() ? " (below hurdle)" : "").append(".\n");
        }
        if (env.ratios() != null && !env.ratios().isEmpty()) {
            user.append("Key ratios: ").append(env.ratios()).append(".\n");
        }
        user.append("Covenants set: ").append(covs.size()).append(".\n");
        if (dec != null) {
            user.append("Routing: required authority ").append(nv(dec.getRequiredAuthority()))
                    .append(", status ").append(nv(dec.getStatus()))
                    .append(dec.getOutcome() == null ? "" : (", outcome " + dec.getOutcome())).append(".\n");
        }
        return safeComplete(LlmRequest.of("proposal-draft", system, user.toString()));
    }

    private LlmResult safeComplete(LlmRequest req) {
        try {
            LlmResult r = llm.complete(req);
            return r == null ? LlmResult.notConfigured() : r;
        } catch (Exception e) {
            return LlmResult.failed(e.getMessage());
        }
    }

    // ----------------------------------------------------------- helpers

    private static String[] bullet(String k, String v) {
        return new String[]{k, v};
    }

    private static String money(double v, String ccy) {
        return String.format(Locale.UK, "%,.0f", v) + (ccy == null ? "" : " " + ccy);
    }

    private static String pct(double v) {
        return String.format(Locale.UK, "%.2f%%", v * 100);
    }

    private static String nv(String s) {
        return s == null || s.isBlank() ? "—" : s;
    }

    private static Map<String, String> format(Map<String, Double> values, Map<String, String> labels) {
        if (values == null) return Map.of();
        Map<String, String> out = new LinkedHashMap<>();
        labels.forEach((k, label) -> {
            Double v = values.get(k);
            if (v != null) out.put(label, String.format(Locale.UK, "%,.0f", v));
        });
        return out;
    }

    private static Map<String, String> formatRatios(Map<String, Double> ratios, Map<String, String> labels) {
        if (ratios == null) return Map.of();
        Map<String, String> out = new LinkedHashMap<>();
        labels.forEach((k, label) -> {
            Double v = ratios.get(k);
            if (v == null) return;
            out.put(label, k.endsWith("MARGIN") || k.endsWith("EQUITY") || k.endsWith("ROE")
                    ? pct(v) : String.format(Locale.UK, "%.2f", v));
        });
        return out;
    }

    /**
     * Minimal Markdown + HTML accumulator (no external templating engine). Tracks an
     * {@code inTable} flag so non-table blocks emitted between tables close the previous
     * tbody/table cleanly, and {@code html()} doesn't emit a dangling
     * {@code </tbody></table>} when no table was ever opened.
     */
    private static class Md {
        private final StringBuilder mdBuf = new StringBuilder();
        private final StringBuilder htmlBuf = new StringBuilder("<article class='credit-proposal'>");
        private boolean inTable = false;

        private void closeTableIfOpen() {
            if (inTable) {
                htmlBuf.append("</tbody></table>");
                inTable = false;
            }
        }

        void h1(String t) { closeTableIfOpen(); mdBuf.append("# ").append(t).append("\n\n"); htmlBuf.append("<h1>").append(esc(t)).append("</h1>"); }
        void h2(String t) { closeTableIfOpen(); mdBuf.append("## ").append(t).append("\n\n"); htmlBuf.append("<h2>").append(esc(t)).append("</h2>"); }
        void line(String s) { closeTableIfOpen(); mdBuf.append(s).append("\n\n"); htmlBuf.append("<p>").append(mdToHtml(s)).append("</p>"); }
        void spacer() { closeTableIfOpen(); mdBuf.append("\n"); htmlBuf.append("<div class='spacer'></div>"); }
        void muted(String s) { closeTableIfOpen(); mdBuf.append("_").append(s).append("_\n\n"); htmlBuf.append("<p class='muted'>").append(esc(s)).append("</p>"); }
        void bullets(String[]... pairs) {
            closeTableIfOpen();
            htmlBuf.append("<ul>");
            for (String[] p : pairs) {
                mdBuf.append("- **").append(p[0]).append(":** ").append(p[1]).append("\n");
                htmlBuf.append("<li><b>").append(esc(p[0])).append(":</b> ").append(esc(p[1])).append("</li>");
            }
            mdBuf.append("\n");
            htmlBuf.append("</ul>");
        }
        void kvBlock(Map<String, String> kv) {
            closeTableIfOpen();
            htmlBuf.append("<table class='kv'>");
            kv.forEach((k, v) -> {
                mdBuf.append("- **").append(k).append(":** ").append(v).append("\n");
                htmlBuf.append("<tr><td>").append(esc(k)).append("</td><td>").append(esc(v)).append("</td></tr>");
            });
            mdBuf.append("\n");
            htmlBuf.append("</table>");
        }
        void table(String[] cols) {
            closeTableIfOpen();
            mdBuf.append("| ").append(String.join(" | ", cols)).append(" |\n");
            mdBuf.append("|").append("---|".repeat(cols.length)).append("\n");
            htmlBuf.append("<table><thead><tr>");
            for (String c : cols) htmlBuf.append("<th>").append(esc(c)).append("</th>");
            htmlBuf.append("</tr></thead><tbody>");
            inTable = true;
        }
        void row(String... cells) {
            mdBuf.append("| ").append(String.join(" | ", cells)).append(" |\n");
            htmlBuf.append("<tr>");
            for (String c : cells) htmlBuf.append("<td>").append(esc(c)).append("</td>");
            htmlBuf.append("</tr>");
        }
        String markdown() { return mdBuf.toString(); }
        String html() { closeTableIfOpen(); return htmlBuf.append("</article>").toString(); }

        private String esc(String s) {
            return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        }
        private String mdToHtml(String s) {
            return esc(s).replaceAll("\\*\\*(.+?)\\*\\*", "<b>$1</b>").replaceAll("_(.+?)_", "<i>$1</i>");
        }
    }
}
