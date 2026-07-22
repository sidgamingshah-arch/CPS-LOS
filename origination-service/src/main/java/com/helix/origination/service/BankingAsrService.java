package com.helix.origination.service;

import com.helix.common.audit.AuditService;
import com.helix.common.llm.LlmClient;
import com.helix.common.llm.LlmRequest;
import com.helix.common.llm.LlmResult;
import com.helix.common.web.ApiException;
import com.helix.origination.dto.BankingAsrDtos.AsrLineRequest;
import com.helix.origination.dto.BankingAsrDtos.CreateAsrRequest;
import com.helix.origination.entity.BankingAsr;
import com.helix.origination.entity.BankingAsr.Status;
import com.helix.origination.entity.BankingAsrLine;
import com.helix.origination.repo.BankingAsrRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Banking Account Statement Review (ASR) engine — deterministic account-conduct analysis
 * captured during origination (CLoM R1-10).
 *
 * <p>{@link #create} computes every conduct metric <b>deterministically</b> from the posted
 * monthly lines (there is no LLM in the figure path). {@link #summary} drafts an <i>optional</i>
 * advisory narrative at the AI boundary via the governed {@link LlmClient}; when no model is
 * wired (default) it falls back to a deterministic template. The narrative is advisory-only and
 * <b>never mutates a computed metric</b>. {@link #confirm} is the named-human gate
 * (DRAFT → CONFIRMED). The ASR never writes to a limit, exposure, rating or price.</p>
 */
@Service
public class BankingAsrService {

    private final BankingAsrRepository repo;
    private final AuditService audit;
    private final LlmClient llm;

    public BankingAsrService(BankingAsrRepository repo, AuditService audit, LlmClient llm) {
        this.repo = repo;
        this.audit = audit;
        this.llm = llm;
    }

    // ---- create + deterministic compute ----

    @Transactional
    public BankingAsr create(CreateAsrRequest req, String actor) {
        if (req.lines() == null || req.lines().isEmpty()) {
            throw ApiException.badRequest("At least one monthly line is required to compute a banking ASR");
        }
        BankingAsr a = new BankingAsr();
        a.setAsrRef(generateRef());
        a.setApplicationRef(req.applicationRef().trim());
        a.setBankName(req.bankName().trim());
        a.setAccountNoMasked(req.accountNoMasked());
        a.setCurrency(req.currency().trim().toUpperCase(Locale.ROOT));
        a.setPeriodFrom(req.periodFrom());
        a.setPeriodTo(req.periodTo());
        a.setSanctionedLimit(req.sanctionedLimit());
        a.setCreatedBy(actor);
        a.setStatus(Status.DRAFT);
        a.setAdvisory(true);

        compute(a, req.lines());

        BankingAsr saved = repo.save(a);

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("applicationRef", saved.getApplicationRef());
        detail.put("months", saved.getLines().size());
        detail.put("averageBankBalance", saved.getAverageBankBalance());
        detail.put("avgUtilisationPct", saved.getAvgUtilisationPct());
        detail.put("sanctionedLimit", saved.getSanctionedLimit());
        audit.engine("BANKING_ASR_COMPUTED", "Application", saved.getApplicationRef(),
                "Computed banking ASR %s over %d month(s) — deterministic account-conduct metrics"
                        .formatted(saved.getAsrRef(), saved.getLines().size()), detail);
        return saved;
    }

    /**
     * Deterministically compute every conduct metric from the posted monthly lines and attach
     * the persisted line rows. Pure arithmetic — averages across months, peak/avg utilisation
     * (drawn ÷ sanctionedLimit), credit/debit summations, cheque-return splits and min/max.
     */
    private void compute(BankingAsr a, List<AsrLineRequest> lines) {
        double sanctioned = a.getSanctionedLimit();
        int months = lines.size();

        double sumAvgBal = 0;
        double sumUtil = 0;
        double peakUtil = 0;
        double totalCredits = 0;
        double totalDebits = 0;
        double inward = 0;
        double outward = 0;
        double minBalance = Double.POSITIVE_INFINITY;
        double maxBalance = Double.NEGATIVE_INFINITY;
        int txnCount = 0;

        List<BankingAsrLine> rows = new ArrayList<>(months);
        for (AsrLineRequest l : lines) {
            if (l.monthLabel() == null || l.monthLabel().isBlank()) {
                throw ApiException.badRequest("Every monthly line needs a monthLabel");
            }
            double monthlyAvgBal = (l.openingBalance() + l.closingBalance()) / 2.0;
            double util = sanctioned > 0 ? l.drawn() / sanctioned : 0.0;
            double monthChequeReturns = l.chequeReturnsInward() + l.chequeReturnsOutward();

            sumAvgBal += monthlyAvgBal;
            sumUtil += util;
            peakUtil = Math.max(peakUtil, util);
            totalCredits += l.totalCredit();
            totalDebits += l.totalDebit();
            inward += l.chequeReturnsInward();
            outward += l.chequeReturnsOutward();
            minBalance = Math.min(minBalance, l.minBalanceInMonth());
            maxBalance = Math.max(maxBalance, l.peakBalance());
            txnCount += l.transactionCount();

            BankingAsrLine row = new BankingAsrLine();
            row.setAsr(a);
            row.setMonthLabel(l.monthLabel().trim());
            row.setOpeningBalance(l.openingBalance());
            row.setClosingBalance(l.closingBalance());
            row.setTotalCredit(l.totalCredit());
            row.setTotalDebit(l.totalDebit());
            row.setPeakBalance(l.peakBalance());
            row.setMinBalanceInMonth(l.minBalanceInMonth());
            row.setChequeReturns(monthChequeReturns);
            row.setUtilisationPct(util);
            rows.add(row);
        }

        a.getLines().clear();
        a.getLines().addAll(rows);

        a.setAverageBankBalance(sumAvgBal / months);
        a.setPeakUtilisationPct(peakUtil);
        a.setAvgUtilisationPct(sumUtil / months);
        a.setTotalCredits(totalCredits);
        a.setTotalDebits(totalDebits);
        a.setCreditSummationMonthlyAvg(totalCredits / months);
        a.setChequeReturnsInward(inward);
        a.setChequeReturnsOutward(outward);
        a.setMinBalance(minBalance == Double.POSITIVE_INFINITY ? 0 : minBalance);
        a.setMaxBalance(maxBalance == Double.NEGATIVE_INFINITY ? 0 : maxBalance);
        a.setTransactionCount(txnCount);
    }

    // ---- read ----

    @Transactional(readOnly = true)
    public BankingAsr get(String asrRef) {
        return repo.findByAsrRef(asrRef)
                .orElseThrow(() -> ApiException.notFound("No banking ASR: " + asrRef));
    }

    @Transactional(readOnly = true)
    public List<BankingAsr> list(String applicationRef) {
        if (applicationRef != null && !applicationRef.isBlank()) {
            return repo.findByApplicationRefOrderByCreatedAtDesc(applicationRef.trim());
        }
        return repo.findAllByOrderByCreatedAtDesc();
    }

    // ---- advisory narrative (AI boundary; never mutates a metric) ----

    /**
     * Draft an advisory narrative summary of the account conduct. Best-effort via the governed
     * {@link LlmClient} boundary; when no model is configured (default) or the call fails, a
     * deterministic template summary is used. The metrics are quoted verbatim and are NEVER
     * changed by this call — the summary is persisted on the same advisory record and audited
     * as an AI event; a human still confirms the review.
     */
    @Transactional
    public BankingAsr summary(String asrRef, String actor) {
        BankingAsr a = get(asrRef);

        String deterministic = templateSummary(a);
        String text = deterministic;
        String model = "banking-asr-summary-v1";

        LlmResult r = safeComplete(llmRequest(a, deterministic));
        boolean llmDrafted = r.usable();
        if (llmDrafted) {
            text = r.text().strip();
            model = r.model();
        }

        a.setAdvisorySummary(text);
        a.setSummaryModel(model);
        a.setAdvisory(true);
        BankingAsr saved = repo.save(a);

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("asrRef", saved.getAsrRef());
        detail.put("applicationRef", saved.getApplicationRef());
        detail.put("advisory", true);
        detail.put("llmDrafted", llmDrafted);
        audit.ai("banking-asr-summary", "BANKING_ASR_SUMMARISED", "Application", saved.getApplicationRef(),
                "Drafted advisory account-conduct summary for %s — advisory, metrics unchanged"
                        .formatted(saved.getAsrRef()), detail);
        return saved;
    }

    private LlmRequest llmRequest(BankingAsr a, String deterministic) {
        String system = "You are an ADVISORY banking account-conduct analyst for wholesale credit. "
                + "Write a short account-statement-review narrative for a credit officer. Reuse the "
                + "supplied figures VERBATIM and invent nothing. The narrative is advisory and human-"
                + "reviewed; it sets no figure or decision. Reply with only the narrative. "
                + "capability=banking-asr-summary";
        String user = "Bank: " + nz(a.getBankName()) + "\nCurrency: " + nz(a.getCurrency())
                + "\nDeterministic metrics (quote verbatim):\n" + deterministic;
        return LlmRequest.of("banking-asr-summary", system, user);
    }

    /** Deterministic fallback narrative — pure string assembly over the computed metrics. */
    private String templateSummary(BankingAsr a) {
        return ("Account conduct over %d month(s) for %s (%s). Average bank balance %s %,.0f; "
                + "average utilisation %.1f%% (peak %.1f%%) of a sanctioned limit of %s %,.0f. "
                + "Total credits %s %,.0f (monthly average %s %,.0f); total debits %s %,.0f. "
                + "Cheque returns: %,.0f inward, %,.0f outward. Balance ranged %s %,.0f to %s %,.0f "
                + "across %d transactions.")
                .formatted(a.getLines().size(), nz(a.getBankName()), nz(a.getAccountNoMasked()),
                        a.getCurrency(), a.getAverageBankBalance(),
                        a.getAvgUtilisationPct() * 100.0, a.getPeakUtilisationPct() * 100.0,
                        a.getCurrency(), a.getSanctionedLimit(),
                        a.getCurrency(), a.getTotalCredits(), a.getCurrency(), a.getCreditSummationMonthlyAvg(),
                        a.getCurrency(), a.getTotalDebits(),
                        a.getChequeReturnsInward(), a.getChequeReturnsOutward(),
                        a.getCurrency(), a.getMinBalance(), a.getCurrency(), a.getMaxBalance(),
                        a.getTransactionCount());
    }

    private LlmResult safeComplete(LlmRequest req) {
        try {
            LlmResult r = llm.complete(req);
            return r == null ? LlmResult.notConfigured() : r;
        } catch (Exception e) {
            return LlmResult.failed(e.getMessage());
        }
    }

    // ---- human confirm gate ----

    /** DRAFT → CONFIRMED. Named-human accountability for the reviewed conduct record. */
    @Transactional
    public BankingAsr confirm(String asrRef, String note, String actor) {
        if (actor == null || actor.isBlank()) {
            throw ApiException.forbiddenAutonomy("A named human actor is required to confirm a banking ASR");
        }
        BankingAsr a = get(asrRef);
        if (a.getStatus() != Status.DRAFT) {
            throw ApiException.conflict("Only a DRAFT banking ASR can be confirmed (is " + a.getStatus() + ")");
        }
        a.setStatus(Status.CONFIRMED);
        a.setConfirmedBy(actor);
        a.setConfirmedAt(Instant.now());
        BankingAsr saved = repo.save(a);

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("asrRef", saved.getAsrRef());
        detail.put("applicationRef", saved.getApplicationRef());
        if (note != null && !note.isBlank()) {
            detail.put("note", note);
        }
        audit.human(actor, "BANKING_ASR_CONFIRMED", "Application", saved.getApplicationRef(),
                "Confirmed banking ASR %s by %s".formatted(saved.getAsrRef(), actor), detail);
        return saved;
    }

    // ---- helpers ----

    private String generateRef() {
        for (int i = 0; i < 8; i++) {
            String ref = "ASR-" + UUID.randomUUID().toString().replace("-", "")
                    .substring(0, 6).toUpperCase(Locale.ROOT);
            if (!repo.existsByAsrRef(ref)) {
                return ref;
            }
        }
        return "ASR-" + UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase(Locale.ROOT);
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
