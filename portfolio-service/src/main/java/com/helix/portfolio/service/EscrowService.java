package com.helix.portfolio.service;

import com.helix.common.audit.AuditService;
import com.helix.common.web.ApiException;
import com.helix.portfolio.client.EscrowThresholdClient;
import com.helix.portfolio.client.EscrowThresholdClient.RagThresholds;
import com.helix.portfolio.dto.EscrowDtos.AccountView;
import com.helix.portfolio.dto.EscrowDtos.BudgetLineRequest;
import com.helix.portfolio.dto.EscrowDtos.BudgetVsActual;
import com.helix.portfolio.dto.EscrowDtos.CategoryStatus;
import com.helix.portfolio.dto.EscrowDtos.CreateAccountRequest;
import com.helix.portfolio.dto.EscrowDtos.TransactionRequest;
import com.helix.portfolio.entity.EscrowAccount;
import com.helix.portfolio.entity.EscrowAccountStatus;
import com.helix.portfolio.entity.EscrowBudgetLine;
import com.helix.portfolio.entity.EscrowDirection;
import com.helix.portfolio.entity.EscrowTransaction;
import com.helix.portfolio.repo.EscrowAccountRepository;
import com.helix.portfolio.repo.EscrowBudgetLineRepository;
import com.helix.portfolio.repo.EscrowTransactionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Escrow monitoring engine. A record / monitoring surface: it hosts escrow accounts,
 * append-only versioned budget lines (a new version supersedes the prior active line for
 * a category, keeping full history), and category-tagged transactions. The signature read
 * is a <b>deterministic</b> budget-vs-actual per category — the sum of tagged transactions
 * vs the active budget line — with a RAG band derived from the {@code VALIDATION_PARAMETER}
 * thresholds (config-as-data, conservative fallback).
 *
 * <p><b>Invariant</b>: this engine NEVER mutates an authoritative figure (ECL / IRAC /
 * exposure / limit). It only writes its own escrow rows + audit events.</p>
 */
@Service
public class EscrowService {

    private static final String SUBJECT = "EscrowAccount";

    private final EscrowAccountRepository accounts;
    private final EscrowBudgetLineRepository budgets;
    private final EscrowTransactionRepository transactions;
    private final EscrowThresholdClient thresholds;
    private final AuditService audit;

    public EscrowService(EscrowAccountRepository accounts, EscrowBudgetLineRepository budgets,
                         EscrowTransactionRepository transactions, EscrowThresholdClient thresholds,
                         AuditService audit) {
        this.accounts = accounts;
        this.budgets = budgets;
        this.transactions = transactions;
        this.thresholds = thresholds;
        this.audit = audit;
    }

    // =============================================================== accounts

    @Transactional
    public EscrowAccount createAccount(CreateAccountRequest req, String actor) {
        if (req == null || req.currency() == null || req.currency().isBlank()) {
            throw ApiException.badRequest("currency is required");
        }
        if (actor == null || actor.isBlank()) {
            throw ApiException.badRequest("actor (X-Actor) is required");
        }
        EscrowAccount a = new EscrowAccount();
        a.setEscrowRef(newRef());
        a.setSubjectType(trimOrNull(req.subjectType()));
        a.setSubjectRef(trimOrNull(req.subjectRef()));
        a.setPurpose(trimOrNull(req.purpose()));
        a.setCurrency(req.currency().trim().toUpperCase());
        a.setOpeningBalance(req.openingBalance() == null ? 0.0 : req.openingBalance());
        a.setStatus(EscrowAccountStatus.ACTIVE);
        a.setCreatedBy(actor);
        EscrowAccount saved = accounts.save(a);

        audit.human(actor, "ESCROW_ACCOUNT_CREATED", SUBJECT, saved.getEscrowRef(),
                "Escrow %s opened for %s (%s %.2f opening)".formatted(
                        saved.getEscrowRef(), safe(saved.getSubjectRef()), saved.getCurrency(),
                        saved.getOpeningBalance()),
                Map.of("subjectRef", safe(saved.getSubjectRef()), "currency", saved.getCurrency(),
                        "openingBalance", saved.getOpeningBalance()));
        return saved;
    }

    @Transactional(readOnly = true)
    public List<EscrowAccount> list(String subjectRef) {
        return subjectRef == null || subjectRef.isBlank()
                ? accounts.findAllByOrderByIdDesc()
                : accounts.findBySubjectRefOrderByIdDesc(subjectRef.trim());
    }

    @Transactional(readOnly = true)
    public AccountView view(String ref) {
        EscrowAccount a = require(ref);
        return new AccountView(a,
                budgets.findByAccountRefAndActiveTrueOrderByCategoryAsc(ref),
                transactions.findByAccountRefOrderByIdDesc(ref));
    }

    // =============================================================== budget lines (append-only, versioned)

    @Transactional
    public EscrowBudgetLine addBudgetLine(String ref, BudgetLineRequest req, String actor) {
        EscrowAccount a = require(ref);
        requireActive(a);
        if (req == null || req.category() == null || req.category().isBlank()) {
            throw ApiException.badRequest("category is required");
        }
        if (req.budgetedAmount() == null || req.budgetedAmount() < 0) {
            throw ApiException.badRequest("budgetedAmount is required and must be >= 0");
        }
        String category = req.category().trim();

        // Supersede the current active line for this category (preserved as history, never deleted).
        Optional<EscrowBudgetLine> current = budgets.findByAccountRefAndCategoryAndActiveTrue(ref, category);
        int nextVersion = 1;
        if (current.isPresent()) {
            EscrowBudgetLine prev = current.get();
            prev.setActive(false);
            budgets.save(prev);
            nextVersion = prev.getVersionNo() + 1;
        }

        EscrowBudgetLine line = new EscrowBudgetLine();
        line.setAccountRef(ref);
        line.setCategory(category);
        line.setBudgetedAmount(req.budgetedAmount());
        line.setVersionNo(nextVersion);
        line.setActive(true);
        line.setEffectiveFrom(parseDate(req.effectiveFrom()));
        line.setNote(trimOrNull(req.note()));
        line.setCreatedBy(actor);
        EscrowBudgetLine saved = budgets.save(line);

        audit.human(actor, "ESCROW_BUDGET_VERSIONED", SUBJECT, ref,
                "Budget line '%s' set to %.2f (v%d) on %s".formatted(
                        category, saved.getBudgetedAmount(), nextVersion, ref),
                Map.of("category", category, "budgetedAmount", saved.getBudgetedAmount(),
                        "versionNo", nextVersion));
        return saved;
    }

    @Transactional(readOnly = true)
    public List<EscrowBudgetLine> budgetHistory(String ref) {
        require(ref);
        return budgets.findByAccountRefOrderByIdDesc(ref);
    }

    // =============================================================== transactions

    @Transactional
    public EscrowTransaction postTransaction(String ref, TransactionRequest req, String actor) {
        EscrowAccount a = require(ref);
        requireActive(a);
        if (req == null || req.amount() == null || req.amount() <= 0) {
            throw ApiException.badRequest("amount is required and must be > 0");
        }
        EscrowDirection direction = parseDirection(req.direction());

        EscrowTransaction t = new EscrowTransaction();
        t.setAccountRef(ref);
        t.setAmount(req.amount());
        t.setDirection(direction);
        t.setTaggedCategory(trimOrNull(req.category()));
        t.setValueDate(parseDate(req.valueDate()));
        t.setMemo(trimOrNull(req.memo()));
        t.setPostedBy(actor);
        EscrowTransaction saved = transactions.save(t);

        audit.human(actor, "ESCROW_TXN_POSTED", SUBJECT, ref,
                "%s %.2f %s tagged '%s' on %s".formatted(
                        direction, saved.getAmount(), a.getCurrency(),
                        safe(saved.getTaggedCategory()), ref),
                Map.of("direction", direction.name(), "amount", saved.getAmount(),
                        "category", safe(saved.getTaggedCategory())));
        return saved;
    }

    @Transactional(readOnly = true)
    public List<EscrowTransaction> transactions(String ref) {
        require(ref);
        return transactions.findByAccountRefOrderByIdDesc(ref);
    }

    // =============================================================== deterministic budget-vs-actual

    /**
     * Deterministic budget-vs-actual per active category. For each active budget line we
     * sum the tagged transactions (DEBIT = spend counted against the budget, CREDIT tracked
     * separately), compute utilisation % and a RAG band from the config thresholds. The read
     * stamps a SYSTEM audit event; it touches no authoritative figure.
     */
    @Transactional
    public BudgetVsActual budgetVsActual(String ref) {
        EscrowAccount a = require(ref);
        RagThresholds th = thresholds.thresholds();

        List<EscrowBudgetLine> active = budgets.findByAccountRefAndActiveTrueOrderByCategoryAsc(ref);
        List<EscrowTransaction> txns = transactions.findByAccountRefOrderByIdDesc(ref);

        List<CategoryStatus> rows = new ArrayList<>();
        double totalBudgeted = 0.0;
        double totalActual = 0.0;
        int worst = 0; // 0 GREEN, 1 AMBER, 2 RED
        for (EscrowBudgetLine line : active) {
            double credited = 0.0;
            double debited = 0.0;
            int count = 0;
            for (EscrowTransaction t : txns) {
                if (line.getCategory().equals(t.getTaggedCategory())) {
                    count++;
                    if (t.getDirection() == EscrowDirection.CREDIT) credited += t.getAmount();
                    else debited += t.getAmount();
                }
            }
            double budget = line.getBudgetedAmount();
            double actual = debited;                    // spend against the budget baseline
            double variance = budget - actual;          // remaining headroom (negative = overspend)
            Double utilisationPct = budget > 0 ? round2(actual / budget * 100.0) : null;
            String rag = ragBand(utilisationPct, actual, budget, th.amberPct(), th.redPct());
            worst = Math.max(worst, rank(rag));
            totalBudgeted += budget;
            totalActual += actual;
            rows.add(new CategoryStatus(line.getCategory(), line.getVersionNo(), round2(budget),
                    round2(credited), round2(debited), round2(actual), round2(variance),
                    utilisationPct, rag, count));
        }
        String overallRag = band(worst);

        audit.engine("ESCROW_BUDGET_ASSESSED", SUBJECT, ref,
                "Budget-vs-actual: %s (%d categories, thresholds %s: amber %.0f%% / red %.0f%%)".formatted(
                        overallRag, rows.size(), th.source(), th.amberPct(), th.redPct()),
                Map.of("overallRag", overallRag, "categories", rows.size(),
                        "thresholdSource", th.source(), "amberPct", th.amberPct(), "redPct", th.redPct(),
                        "totalBudgeted", round2(totalBudgeted), "totalActual", round2(totalActual)));

        return new BudgetVsActual(a.getEscrowRef(), a.getCurrency(), th.amberPct(), th.redPct(),
                th.source(), overallRag, round2(totalBudgeted), round2(totalActual), rows);
    }

    // =============================================================== internals

    private EscrowAccount require(String ref) {
        return accounts.findByEscrowRef(ref)
                .orElseThrow(() -> ApiException.notFound("Escrow account " + ref + " not found"));
    }

    private void requireActive(EscrowAccount a) {
        if (a.getStatus() != EscrowAccountStatus.ACTIVE) {
            throw ApiException.conflict("Escrow account " + a.getEscrowRef() + " is " + a.getStatus());
        }
    }

    private static String ragBand(Double utilisationPct, double actual, double budget,
                                  double amberPct, double redPct) {
        if (budget <= 0) {
            return actual > 0 ? "RED" : "GREEN";
        }
        double util = utilisationPct == null ? 0.0 : utilisationPct;
        if (util >= redPct) return "RED";
        if (util >= amberPct) return "AMBER";
        return "GREEN";
    }

    private static int rank(String rag) {
        return switch (rag) {
            case "RED" -> 2;
            case "AMBER" -> 1;
            default -> 0;
        };
    }

    private static String band(int rank) {
        return switch (rank) {
            case 2 -> "RED";
            case 1 -> "AMBER";
            default -> "GREEN";
        };
    }

    private static EscrowDirection parseDirection(String s) {
        if (s == null || s.isBlank()) {
            throw ApiException.badRequest("direction is required (CREDIT or DEBIT)");
        }
        try {
            return EscrowDirection.valueOf(s.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("Unknown direction '" + s + "' (expected CREDIT or DEBIT)");
        }
    }

    private static LocalDate parseDate(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return LocalDate.parse(s.trim());
        } catch (DateTimeParseException e) {
            throw ApiException.badRequest("Invalid date '" + s + "' (expected ISO yyyy-MM-dd)");
        }
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    private static String trimOrNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    private static String newRef() {
        final String alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
        StringBuilder sb = new StringBuilder("ESC-");
        for (int i = 0; i < 6; i++) {
            sb.append(alphabet.charAt(ThreadLocalRandom.current().nextInt(alphabet.length())));
        }
        return sb.toString();
    }
}
