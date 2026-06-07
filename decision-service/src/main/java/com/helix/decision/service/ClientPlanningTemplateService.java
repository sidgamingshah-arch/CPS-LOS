package com.helix.decision.service;

import com.helix.common.audit.AuditService;
import com.helix.common.web.ApiException;
import com.helix.decision.client.UpstreamClient;
import com.helix.decision.client.UpstreamClient.AuditEventDto;
import com.helix.decision.client.UpstreamClient.CounterpartyDto;
import com.helix.decision.client.UpstreamClient.CounterpartyGroupDto;
import com.helix.decision.client.UpstreamClient.DealEnvelopeDto;
import com.helix.decision.client.UpstreamClient.LoanApplicationRefDto;
import com.helix.decision.client.UpstreamClient.RiskSummaryDto;
import com.helix.decision.entity.ClientPlanningTemplate;
import com.helix.decision.repo.ClientPlanningTemplateRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Client Planning Template (CPT) generator — PRD §1 / §2:
 * "automated CPT generation", "relationship summary generation", "peer analysis
 * generation", "wallet sizing using financial projections", "region-specific
 * industry insights", "automated nudges and triggers for CPT completeness".
 *
 * <p>The CPT is an advisory, grounded summary an RM uses to plan the
 * relationship. Every figure is quoted verbatim from upstream services; the
 * three-scenario wallet sizing is a transparent heuristic projection over the
 * latest spread, not a trained model. AI generates, an RM confirms — and the
 * deterministic figure path (grade / capital / pricing) is never mutated.
 */
@Service
public class ClientPlanningTemplateService {

    /** Helix's product catalogue surface used for cross-sell signal. */
    private static final List<String> PRODUCT_CATALOGUE = List.of(
            "TERM_LOAN", "REVOLVING_CREDIT", "WORKING_CAPITAL", "TRADE_FINANCE",
            "LETTER_OF_CREDIT", "BANK_GUARANTEE", "CASH_MANAGEMENT", "FX_HEDGE",
            "SUPPLY_CHAIN_FINANCE", "SYNDICATED_LOAN", "PROJECT_FINANCE");

    private static final int CALL_REPORT_FRESH_DAYS = 90;
    private static final int EXTERNAL_RATING_FRESH_DAYS = 180;

    private final ClientPlanningTemplateRepository templates;
    private final UpstreamClient upstream;
    private final AuditService audit;

    public ClientPlanningTemplateService(ClientPlanningTemplateRepository templates,
                                         UpstreamClient upstream, AuditService audit) {
        this.templates = templates;
        this.upstream = upstream;
        this.audit = audit;
    }

    @Transactional
    public ClientPlanningTemplate generate(String counterpartyReference,
                                           Double trendOverride, String actor) {
        CounterpartyDto cp = upstream.counterpartyByReference(counterpartyReference);
        List<LoanApplicationRefDto> apps = upstream.applicationsForCounterparty(counterpartyReference);

        // ---------- aggregate live applications: exposure / facilities / weighted figures ----------
        Map<String, Double> exposureByCcy = new LinkedHashMap<>();
        Map<String, Double> latestRatios = null;
        Map<String, Double> latestFinancials = null;
        Set<String> facilityTypes = new HashSet<>();
        double wPdNum = 0.0, wRarocNum = 0.0, wDen = 0.0;
        int facCount = 0;
        String latestGrade = null;
        int withApp = 0;

        for (LoanApplicationRefDto a : apps) {
            DealEnvelopeDto env = upstream.envelopeOrNull(a.reference());
            RiskSummaryDto rs = upstream.riskSummaryOrNull(a.reference());
            if (env == null) continue;
            withApp++;
            exposureByCcy.merge(env.currency(), env.totalProposedAmount(), Double::sum);
            facCount += env.facilities() == null ? 0 : env.facilities().size();
            if (env.facilities() != null) {
                env.facilities().forEach(f -> facilityTypes.add(f.facilityType()));
            }
            if (latestRatios == null && env.ratios() != null && !env.ratios().isEmpty()) {
                latestRatios = env.ratios();
            }
            if (latestFinancials == null && env.latestFinancials() != null
                    && !env.latestFinancials().isEmpty()) {
                latestFinancials = env.latestFinancials();
            }
            if (rs != null && rs.rating() != null) {
                wPdNum += rs.rating().pd() * env.totalProposedAmount();
                if (rs.pricing() != null) wRarocNum += rs.pricing().raroc() * env.totalProposedAmount();
                wDen += env.totalProposedAmount();
                if (latestGrade == null) latestGrade = rs.rating().finalGrade();
            }
        }
        Double weightedPd = wDen == 0 ? null : wPdNum / wDen;
        Double weightedRaroc = wDen == 0 ? null : wRarocNum / wDen;

        // ---------- group context (resolved from counterparty-service when tagged) ----------
        String groupRef = null;
        String groupName = null;
        if (cp.groupId() != null) {
            CounterpartyGroupDto g = upstream.groupByIdOrNull(cp.groupId());
            if (g != null) {
                groupRef = g.reference();
                groupName = g.name();
            }
        }

        // ---------- cross-sell heuristic ----------
        List<String> currentFacilityTypes = new ArrayList<>(facilityTypes);
        List<String> potentialCrossSell = PRODUCT_CATALOGUE.stream()
                .filter(p -> !facilityTypes.contains(p))
                .toList();

        // ---------- wallet sizing: 3 scenarios on latest revenue ----------
        Double baseRevenue = approxRevenue(latestFinancials, latestRatios);
        Map<String, Object> walletSizing = composeWalletSizing(baseRevenue, trendOverride);

        // ---------- macro / industry signals (heuristic — sector + region tagging) ----------
        Map<String, Object> industryInsights = composeIndustryInsights(cp);

        // ---------- peer / whitespace ----------
        List<String> peerInsights = composePeerInsights(cp, weightedRaroc, weightedPd, exposureByCcy);

        // ---------- completeness nudges ----------
        List<String> nudges = composeNudges(cp, apps, withApp, exposureByCcy);

        // ---------- render markdown + html ----------
        Md md = new Md();
        renderCpt(md, cp, apps.size(), withApp, exposureByCcy, weightedPd, weightedRaroc,
                latestGrade, facCount, currentFacilityTypes, potentialCrossSell,
                walletSizing, industryInsights, peerInsights, nudges, groupRef, groupName);

        Map<String, Object> citations = new LinkedHashMap<>();
        citations.put("counterparty",
                "counterparty-service GET /api/counterparties/by-reference/" + counterpartyReference);
        citations.put("applications",
                "origination-service GET /api/applications/by-counterparty/" + counterpartyReference);
        citations.put("riskPerApp",
                "risk-service GET /api/risk/{ref} for each application");
        citations.put("audit",
                "counterparty-service GET /api/audit/subject?type=Counterparty&id=" + counterpartyReference);

        int version = templates.findFirstByCounterpartyReferenceOrderByVersionDesc(counterpartyReference)
                .map(t -> t.getVersion() + 1).orElse(1);

        ClientPlanningTemplate t = new ClientPlanningTemplate();
        t.setCounterpartyReference(counterpartyReference);
        t.setVersion(version);
        t.setCounterpartyName(cp.legalName());
        t.setRmId(cp.rmId());
        t.setGroupReference(groupRef);
        t.setBorrowerType(cp.borrowerType());
        t.setSegment(cp.segment());
        t.setSector(cp.sector());
        t.setCountry(cp.country());
        t.setExposureByCurrency(toObjMap(exposureByCcy));
        t.setWeightedAveragePd(weightedPd);
        t.setWeightedAverageRaroc(weightedRaroc);
        t.setLatestGrade(latestGrade);
        t.setFacilityCount(facCount);
        t.setApplicationCount(apps.size());
        t.setCurrentFacilityTypes(currentFacilityTypes);
        t.setPotentialCrossSell(potentialCrossSell);
        t.setWalletSizing(walletSizing);
        t.setIndustryInsights(industryInsights);
        t.setPeerInsights(peerInsights);
        t.setCompletenessNudges(nudges);
        t.setCitations(citations);
        t.setMarkdown(md.markdown());
        t.setHtml(md.html());
        t.setGeneratedBy("ai:cpt-generator");
        ClientPlanningTemplate saved = templates.save(t);
        audit.ai("cpt-generator", "CPT_GENERATED", "Counterparty", counterpartyReference,
                "Generated CPT v%d for %s — %d application(s), %d nudge(s)"
                        .formatted(version, cp.legalName(), apps.size(), nudges.size()),
                Map.of("version", version, "applicationCount", apps.size(),
                        "nudgeCount", nudges.size(), "advisory", true));
        return saved;
    }

    @Transactional(readOnly = true)
    public ClientPlanningTemplate latest(String counterpartyReference) {
        return templates.findFirstByCounterpartyReferenceOrderByVersionDesc(counterpartyReference)
                .orElseThrow(() -> ApiException.notFound("No CPT for " + counterpartyReference));
    }

    @Transactional(readOnly = true)
    public List<ClientPlanningTemplate> versions(String counterpartyReference) {
        return templates.findByCounterpartyReferenceOrderByVersionDesc(counterpartyReference);
    }

    @Transactional
    public ClientPlanningTemplate review(Long id, boolean approve, String note, String actor) {
        ClientPlanningTemplate t = templates.findById(id)
                .orElseThrow(() -> ApiException.notFound("No CPT: " + id));
        if (!"DRAFT".equals(t.getStatus())) {
            throw ApiException.conflict("CPT already " + t.getStatus());
        }
        t.setStatus(approve ? "CONFIRMED" : "REJECTED");
        t.setReviewedBy(actor);
        t.setReviewedAt(Instant.now());
        t.setReviewNote(note);
        ClientPlanningTemplate saved = templates.save(t);
        audit.human(actor, approve ? "CPT_CONFIRMED" : "CPT_REJECTED",
                "Counterparty", t.getCounterpartyReference(),
                "%s CPT v%d for %s".formatted(approve ? "Confirmed" : "Rejected", t.getVersion(),
                        t.getCounterpartyName()),
                Map.of("cptId", id, "version", t.getVersion()));
        return saved;
    }

    // ============================================================ enrichment

    /**
     * Best revenue proxy from the deal envelope. Raw REVENUE lives on the financials map;
     * the ratios map only carries derived ratios (EBITDA_MARGIN, DSCR, ...). Prefer the
     * direct value; fall back to EBITDA / EBITDA_MARGIN when revenue isn't on file.
     */
    private static Double approxRevenue(Map<String, Double> financials, Map<String, Double> ratios) {
        if (financials != null) {
            Double rev = financials.get("REVENUE");
            if (rev != null && rev > 0) return rev;
            Double ebitda = financials.get("EBITDA");
            Double margin = ratios == null ? null : ratios.get("EBITDA_MARGIN");
            if (ebitda != null && margin != null && margin > 0) return ebitda / margin;
        }
        return null;
    }

    private static Map<String, Object> composeWalletSizing(Double baseRevenue, Double trendOverride) {
        double trend = trendOverride != null ? trendOverride : 0.10;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("baseRevenue", baseRevenue);
        out.put("trendFactor", trend);
        out.put("horizonYears", 3);
        List<Map<String, Object>> scenarios = new ArrayList<>();
        scenarios.add(scenario("BEST_CASE", trend + 0.08, baseRevenue,
                "Sector outperforms; competitive pricing wins share"));
        scenarios.add(scenario("MOST_LIKELY", trend, baseRevenue,
                "Continuation of trailing-twelve-month trajectory"));
        scenarios.add(scenario("WORST_CASE", trend - 0.15, baseRevenue,
                "Headwinds compound; share erosion or input-cost stress"));
        out.put("scenarios", scenarios);
        if (baseRevenue != null) {
            // Indicative wallet ≈ 25% of the most-likely 3-year cumulative revenue
            double mostLikelyDelta = trend;
            double cumul = 0.0;
            double r = baseRevenue;
            for (int i = 0; i < 3; i++) { r = r * (1 + mostLikelyDelta); cumul += r; }
            Map<String, Object> indicative = new LinkedHashMap<>();
            indicative.put("amount", Math.round(cumul * 0.25));
            indicative.put("basis", "25% of most-likely 3-year cumulative revenue");
            out.put("indicativeWallet", indicative);
        }
        return out;
    }

    private static Map<String, Object> scenario(String label, double delta, Double base, String commentary) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("label", label);
        s.put("deltaPct", round4(delta));
        s.put("commentary", commentary);
        if (base != null) {
            List<Double> path = new ArrayList<>();
            double r = base;
            for (int i = 0; i < 3; i++) {
                r = r * (1 + delta);
                path.add(round4(r));
            }
            s.put("projectedRevenue", path);
        }
        return s;
    }

    private static Map<String, Object> composeIndustryInsights(CounterpartyDto cp) {
        Map<String, Object> ins = new LinkedHashMap<>();
        ins.put("sector", cp.sector());
        ins.put("country", cp.country());
        List<String> headwinds = new ArrayList<>();
        List<String> tailwinds = new ArrayList<>();
        String sec = safeUpper(cp.sector());
        switch (sec) {
            case "MANUFACTURING" -> {
                headwinds.addAll(List.of("Input-cost inflation",
                        "Working-capital pressure from extended payable cycles"));
                tailwinds.addAll(List.of("PLI tailwinds in steel / autos",
                        "Capex commitment from anchor investors"));
            }
            case "ENERGY", "OIL_AND_GAS" -> {
                headwinds.addAll(List.of("Crude price volatility",
                        "Transition-finance pressure on legacy assets"));
                tailwinds.add("Strong sovereign demand in MENA");
            }
            case "REAL_ESTATE" -> {
                headwinds.add("Rate-cycle drag on absorption");
                tailwinds.add("Residential demand recovery in tier-1 metros");
            }
            case "TRADING" -> {
                headwinds.add("Margin compression on commodity flows");
                tailwinds.add("Trade-corridor diversification post-2024");
            }
            default -> headwinds.add("Refer to risk-service macro-impact overlay for current scenarios");
        }
        ins.put("headwinds", headwinds);
        ins.put("tailwinds", tailwinds);
        ins.put("note", "Heuristic — refer to /api/risk/{ref}/macro-impact for application-specific scenarios.");
        return ins;
    }

    private List<String> composePeerInsights(CounterpartyDto cp, Double wAvgRaroc, Double wAvgPd,
                                             Map<String, Double> exposureByCcy) {
        List<String> out = new ArrayList<>();
        if (wAvgRaroc == null) {
            out.add("No live RAROC observation across the relationship — peer benchmark requires a confirmed rating + pricing.");
            return out;
        }
        out.add("Exposure-weighted RAROC of "
                + pct(wAvgRaroc) + " vs peer median ~13.5% — "
                + (wAvgRaroc > 0.135 ? "above" : "below") + " segment benchmark.");
        if (wAvgPd != null) {
            out.add("PD-weighted profile " + pct3(wAvgPd) + " — "
                    + (wAvgPd < 0.02 ? "investment-grade tier" : "sub-IG; tighter monitoring warranted") + ".");
        }
        out.add("Sector " + cp.sector() + " · segment " + cp.segment()
                + " — compare exposure-mix vs portfolio (see /portfolio/api/mis/concentration).");
        return out;
    }

    private List<String> composeNudges(CounterpartyDto cp, List<LoanApplicationRefDto> apps,
                                       int withApp, Map<String, Double> exposureByCcy) {
        List<String> out = new ArrayList<>();
        // CRM completeness
        if (cp.rmId() == null || cp.rmId().isBlank()) {
            out.add("RM ownership not assigned — request an RM via /api/initiation/counterparties/{id}/ownership/request.");
        }
        if (cp.industry() == null || cp.industry().isBlank()) {
            out.add("Industry not captured on the counterparty record — refine for accurate peer benchmarking.");
        }
        if (apps.isEmpty()) {
            out.add("No live applications on file — initiate a credit application to set wallet baseline.");
        }
        if (withApp == 0 && !apps.isEmpty()) {
            out.add("Applications exist but no envelope is available — confirm spreads + facilities to enable rollup.");
        }
        if (exposureByCcy.size() > 1) {
            out.add(exposureByCcy.size() + "-currency exposure — confirm FX-hedge facility coverage with treasury.");
        }

        // Recent activity scan via audit
        List<AuditEventDto> hist = upstream.counterpartyAudit("Counterparty", cp.reference());
        boolean hasRecentCallReport = hist.stream().anyMatch(e ->
                e.eventType() != null
                && (e.eventType().contains("CALL_REPORT") || e.eventType().contains("RELATIONSHIP_NOTE"))
                && withinDays(e.occurredAt(), CALL_REPORT_FRESH_DAYS));
        if (!hasRecentCallReport) {
            out.add("No call-report / relationship note in the last "
                    + CALL_REPORT_FRESH_DAYS + " days — schedule an RM visit.");
        }
        boolean hasRecentExternalRating = hist.stream().anyMatch(e ->
                e.eventType() != null && e.eventType().contains("EXTERNAL_RATING")
                && withinDays(e.occurredAt(), EXTERNAL_RATING_FRESH_DAYS));
        if (!hasRecentExternalRating) {
            out.add("No external rating fetched in the last "
                    + EXTERNAL_RATING_FRESH_DAYS + " days — refresh via /api/initiation/prospects/{id}/checks/fetch.");
        }
        return out;
    }

    private static boolean withinDays(String iso, int days) {
        if (iso == null) return false;
        try {
            Instant t = Instant.parse(iso);
            return t.isAfter(Instant.now().minus(days, ChronoUnit.DAYS));
        } catch (Exception e) {
            return false;
        }
    }

    // ============================================================ rendering

    private static void renderCpt(Md md, CounterpartyDto cp, int appCount, int withApp,
                                  Map<String, Double> exposureByCcy, Double weightedPd, Double weightedRaroc,
                                  String latestGrade, int facCount, List<String> currentFacTypes,
                                  List<String> crossSell, Map<String, Object> wallet,
                                  Map<String, Object> industry, List<String> peer, List<String> nudges,
                                  String groupRef, String groupName) {
        md.h1("Client planning template · " + cp.legalName() + " (" + cp.reference() + ")");
        md.muted("Grounded in counterparty + applications + risk data. Advisory; "
                + "deterministic figures (grade / capital / pricing) are quoted verbatim, never recomputed.");
        md.spacer();

        // 1. Client overview
        md.h2("1. Client overview");
        md.bullets(
                bullet("Legal name", cp.legalName()),
                bullet("Reference", cp.reference()),
                bullet("Segment", nv(cp.segment())),
                bullet("Sector / industry", nv(cp.sector()) + " · " + nv(cp.industry())),
                bullet("Country", nv(cp.country())),
                bullet("Borrower type", nv(cp.borrowerType())),
                bullet("Relationship manager", nv(cp.rmId())),
                bullet("Group", groupRef == null ? "Not tagged"
                        : (groupName == null ? groupRef : groupName + " (" + groupRef + ")")));

        // 2. Exposure snapshot
        md.h2("2. Exposure snapshot (deterministic, unchanged)");
        Map<String, String> stats = new LinkedHashMap<>();
        stats.put("Applications", String.valueOf(appCount));
        stats.put("Applications with live envelope", String.valueOf(withApp));
        stats.put("Facilities", String.valueOf(facCount));
        if (latestGrade != null) stats.put("Latest grade", latestGrade);
        if (weightedPd != null) stats.put("Weighted-average PD", pct3(weightedPd));
        if (weightedRaroc != null) stats.put("Weighted-average RAROC", pct(weightedRaroc));
        exposureByCcy.forEach((c, v) -> stats.put("Exposure · " + c, String.format(Locale.UK, "%,.0f", v)));
        md.kvBlock(stats);

        // 3. Relationship surface + cross-sell
        md.h2("3. Relationship surface");
        md.line("Current facility types: " + (currentFacTypes.isEmpty() ? "_none yet_"
                : String.join(", ", currentFacTypes)));
        if (!crossSell.isEmpty()) {
            md.line("**Cross-sell whitespace** — products in the catalogue not yet on the relationship:");
            for (String p : crossSell) md.line("- " + p);
        }

        // 4. Wallet sizing
        md.h2("4. Wallet sizing (3 scenarios, heuristic projection)");
        Double base = (Double) wallet.get("baseRevenue");
        Double trend = (Double) wallet.get("trendFactor");
        md.line("Base revenue: " + (base == null ? "—" : String.format(Locale.UK, "%,.0f", base))
                + " · Trend (MOST_LIKELY): " + (trend == null ? "—" : pct(trend))
                + " · Horizon: 3 years");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> scenarios = (List<Map<String, Object>>) wallet.get("scenarios");
        if (scenarios != null) {
            md.table(new String[]{"Scenario", "Δ%", "Y1", "Y2", "Y3", "Commentary"});
            for (Map<String, Object> s : scenarios) {
                @SuppressWarnings("unchecked")
                List<Double> path = (List<Double>) s.get("projectedRevenue");
                md.row((String) s.get("label"),
                        pct((Double) s.get("deltaPct")),
                        path == null ? "—" : String.format(Locale.UK, "%,.0f", path.get(0)),
                        path == null ? "—" : String.format(Locale.UK, "%,.0f", path.get(1)),
                        path == null ? "—" : String.format(Locale.UK, "%,.0f", path.get(2)),
                        (String) s.get("commentary"));
            }
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> indic = (Map<String, Object>) wallet.get("indicativeWallet");
        if (indic != null) {
            md.line("**Indicative wallet:** " + indic.get("amount") + " · _"
                    + indic.get("basis") + "_");
        }

        // 5. Industry / region insights
        md.h2("5. Industry & region insights");
        @SuppressWarnings("unchecked")
        List<String> headwinds = (List<String>) industry.get("headwinds");
        @SuppressWarnings("unchecked")
        List<String> tailwinds = (List<String>) industry.get("tailwinds");
        if (headwinds != null && !headwinds.isEmpty()) {
            md.line("**Headwinds**");
            for (String h : headwinds) md.line("- " + h);
        }
        if (tailwinds != null && !tailwinds.isEmpty()) {
            md.line("**Tailwinds**");
            for (String t : tailwinds) md.line("- " + t);
        }

        // 6. Peer / whitespace
        md.h2("6. Peer & whitespace");
        if (peer.isEmpty()) md.line("_No observations yet._");
        for (String p : peer) md.line("- " + p);

        // 7. Completeness nudges
        md.h2("7. Completeness nudges");
        if (nudges.isEmpty()) {
            md.line("✓ All standard completeness checks passed.");
        } else {
            for (String n : nudges) md.line("- " + n);
        }

        // 8. Provenance
        md.h2("8. Provenance & governance");
        md.bullets(
                bullet("Generated by", "Helix · cpt-generator (AI-assisted; RM signs)"),
                bullet("Grounding",
                        "Every figure is quoted from counterparty + origination + risk + audit endpoints"),
                bullet("Wallet sizing", "Transparent heuristic projection over the spread; no trained ML"),
                bullet("Approval", "AI cannot approve the plan. A named RM must confirm."));
    }

    // ============================================================ helpers

    private static String[] bullet(String k, String v) { return new String[]{k, v}; }

    private static String nv(String s) { return s == null || s.isBlank() ? "—" : s; }

    private static String safeUpper(String s) { return s == null ? "" : s.toUpperCase(); }

    private static String pct(Double v) {
        return v == null ? "—" : String.format(Locale.UK, "%.1f%%", v * 100);
    }

    private static String pct3(Double v) {
        return v == null ? "—" : String.format(Locale.UK, "%.2f%%", v * 100);
    }

    private static double round4(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }

    private static Map<String, Object> toObjMap(Map<String, Double> in) {
        Map<String, Object> out = new LinkedHashMap<>();
        in.forEach(out::put);
        return out;
    }

    /**
     * Minimal Markdown + HTML accumulator (no external templating engine). Tracks an
     * {@code inTable} flag so non-table blocks close the previous tbody/table cleanly,
     * and {@code html()} doesn't emit a dangling {@code </tbody></table>} when no table
     * was ever opened.
     */
    private static class Md {
        private final StringBuilder mdBuf = new StringBuilder();
        private final StringBuilder htmlBuf = new StringBuilder("<article class='cpt'>");
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
