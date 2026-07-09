package com.helix.limit.service;

import com.helix.common.audit.AuditService;
import com.helix.common.money.Money;

import java.math.BigDecimal;
import com.helix.common.web.ApiException;
import com.helix.limit.dto.Dtos.ActionResult;
import com.helix.limit.dto.Dtos.ExposureCheckResult;
import com.helix.limit.dto.Dtos.UtilisationAction;
import com.helix.limit.dto.Dtos.UtilisationRequest;
import com.helix.limit.dto.Dtos.UtilisationResponse;
import com.helix.limit.dto.Dtos.ValidationCheck;
import com.helix.limit.dto.Dtos.ValidationResult;
import com.helix.common.rbac.ActorDirectory;
import com.helix.common.rbac.ProtectedAction;
import com.helix.limit.entity.CountryLimit;
import com.helix.limit.entity.LimitNode;
import com.helix.limit.entity.Utilisation;
import com.helix.limit.repo.CountryLimitRepository;
import com.helix.limit.repo.LimitNodeRepository;
import com.helix.limit.repo.UtilisationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The product-processor transaction interface (PRD Validation & Utilisation APIs).
 * Validation runs status / expiry / tenor / available / exposure checks; utilisation
 * applies UTILISE / RELEASE / RESERVE / REVERSAL and rolls balances up the tree to
 * the obligor root. An override flag force-utilises past available/exposure breaches
 * (corporate loans) but never past a FROZEN/CLOSED or expired line.
 */
@Service
public class UtilisationService {

    private final LimitNodeRepository nodes;
    private final UtilisationRepository ledger;
    private final LimitService limits;
    private final FxService fx;
    private final CountryLimitRepository countryLimits;
    private final ActorDirectory roles;
    private final AuditService audit;

    public UtilisationService(LimitNodeRepository nodes, UtilisationRepository ledger, LimitService limits,
                              FxService fx, CountryLimitRepository countryLimits, ActorDirectory roles,
                              AuditService audit) {
        this.nodes = nodes;
        this.ledger = ledger;
        this.limits = limits;
        this.fx = fx;
        this.countryLimits = countryLimits;
        this.roles = roles;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public ValidationResult validate(String cif, String lineId, double amount, String currency, Integer tenorMonths) {
        LimitNode n = nodes.findByReference(lineId).orElse(null);
        List<ValidationCheck> checks = new ArrayList<>();
        if (n == null || (cif != null && !cif.equals(n.getCif()))) {
            return new ValidationResult(false, lineId, "Unknown line for CIF", List.of(), 0, null, List.of());
        }
        double amtBase = fx.toBase(amount, currency == null ? n.getCurrency() : currency);

        checks.add(new ValidationCheck("status_active", "ACTIVE".equals(n.getStatus()), "status=" + n.getStatus()));
        boolean notExpired = n.getExpiryDate() == null || !n.getExpiryDate().isBefore(LocalDate.now());
        checks.add(new ValidationCheck("not_expired", notExpired,
                n.getExpiryDate() == null ? "no expiry" : "expiry=" + n.getExpiryDate()));
        boolean tenorOk = tenorMonths == null || n.getTenorMonths() == null || tenorMonths <= n.getTenorMonths();
        checks.add(new ValidationCheck("tenor_within_facility", tenorOk,
                "requested=%s facility=%s".formatted(tenorMonths, n.getTenorMonths())));
        BigDecimal tol = Money.of(1e-6);
        // D5(2) INTERCHANGEABILITY POOLED CAP — a fungible node bound to an interchangeable group binds
        // against the GROUP's pooled headroom (Σ member caps − Σ member usage), letting a member draw
        // into an idle sibling's headroom. Non-grouped nodes keep the strict own-node check; a root-level
        // group (no parent) has no sibling pool and falls back to own-node.
        boolean grouped = n.isFungible() && n.getInterchangeableGroup() != null && n.getParentId() != null;
        if (grouped) {
            List<LimitNode> members = nodes.findByParentIdAndInterchangeableGroup(
                    n.getParentId(), n.getInterchangeableGroup());
            BigDecimal cap = Money.ZERO, used = Money.ZERO;
            for (LimitNode m : members) {
                cap = Money.add(cap, m.getBaseAmount());
                BigDecimal mu = m.isRevolving() ? m.getOutstanding() : m.getCumulativeDrawn();
                used = Money.add(used, Money.add(mu, m.getReserved()));
            }
            BigDecimal pooled = Money.nonNegative(Money.sub(cap, used));
            boolean poolAvail = Money.of(amtBase).compareTo(Money.add(pooled, tol)) <= 0;
            checks.add(new ValidationCheck("group_available:" + n.getInterchangeableGroup(), poolAvail,
                    "need %.0f, group %s pooled available %.0f (base) across %d members".formatted(
                            amtBase, n.getInterchangeableGroup(), Money.asDouble(pooled), members.size())));
        } else {
            boolean nodeAvail = Money.of(amtBase).compareTo(Money.add(n.available(), tol)) <= 0;
            checks.add(new ValidationCheck("line_available", nodeAvail,
                    "need %.0f, available %.0f (base)".formatted(amtBase, Money.asDouble(n.available()))));
        }
        LimitNode root = root(n);
        boolean rootAvail = Money.of(amtBase).compareTo(Money.add(root.available(), tol)) <= 0;
        checks.add(new ValidationCheck("obligor_available", rootAvail,
                "need %.0f, obligor available %.0f (base)".formatted(amtBase, Money.asDouble(root.available()))));

        // D5(1) INTERMEDIATE-PARENT HEADROOM — the amount must also fit EVERY ancestor strictly between
        // the leaf and the obligor root. adjust() rolls a confirmed draw up this same parentId chain, so
        // each ancestor's available() is roll-up-consistent. Root is already covered by obligor_available.
        Long ancestorId = n.getParentId();
        while (ancestorId != null && !ancestorId.equals(root.getId())) {
            LimitNode anc = nodes.findById(ancestorId).orElse(null);
            if (anc == null) break;
            boolean ancAvail = Money.of(amtBase).compareTo(Money.add(anc.available(), tol)) <= 0;
            checks.add(new ValidationCheck("parent_available:" + anc.getCode(), ancAvail,
                    "need %.0f, parent %s available %.0f (base)".formatted(
                            amtBase, anc.getCode(), Money.asDouble(anc.available()))));
            ancestorId = anc.getParentId();
        }

        // Per-transaction exposure uses the single-name (obligor) norm only; sector/
        // geography concentration is a portfolio/sanction-level review (see /exposure
        // and portfolio-service concentration), not a drawdown-time hard stop.
        ExposureCheckResult exp = limits.exposureCheck(n.getCif(), amtBase);
        exp.checks().stream().filter(c -> "single_name".equals(c.name())).forEach(checks::add);

        // D8 COUNTRY CAP — bind only when a CountryLimit row is configured for the obligor's country.
        // root.getCountry() carries the deal jurisdiction ("IN-RBI"); normalise to the CountryLimit key
        // ("IN"). Absence of a row = no cap = pass (non-blocking) — exactly like an unconfigured cap.
        String ck = countryKey(root.getCountry());
        CountryLimit cl = ck == null ? null : countryLimits.findByCountry(ck).orElse(null);
        if (cl != null) {
            double projectedCountry = cl.getOutstanding() + amtBase;
            checks.add(new ValidationCheck("country_norm",
                    projectedCountry <= cl.getOverallLimit() + 1e-6,
                    "%.0f + %.0f vs country cap %.0f (%s)".formatted(
                            cl.getOutstanding(), amtBase, cl.getOverallLimit(), ck)));
        }

        boolean success = checks.stream().allMatch(ValidationCheck::pass);
        List<String> terms = List.of(
                "Tenor <= %s months".formatted(n.getTenorMonths()),
                "Facility status must be ACTIVE",
                "Country/sector exposure norms apply");
        String msg = success ? "All validations passed" : "One or more validations failed";
        return new ValidationResult(success, lineId, msg, checks, round(Money.asDouble(n.available())), FxService.BASE, terms);
    }

    @Transactional
    public UtilisationResponse apply(UtilisationRequest req, String actor) {
        List<ActionResult> results = new ArrayList<>();
        boolean allOk = true;
        for (UtilisationAction a : req.actions()) {
            ActionResult r = applyOne(req.cif(), a, req.overrideFlag(), req.productProcessor(), actor);
            results.add(r);
            allOk = allOk && r.success();
        }
        return new UtilisationResponse(req.cif(), allOk, results);
    }

    private ActionResult applyOne(String cif, UtilisationAction a, boolean override, String pp, String actor) {
        LimitNode n = nodes.findByReference(a.lineId()).orElse(null);
        if (n == null || (cif != null && !cif.equals(n.getCif()))) {
            return new ActionResult(a.lineId(), a.action(), false, "Unknown line for CIF", 0, 0);
        }
        String action = a.action().toUpperCase();
        double amtBase = fx.toBase(a.amount(), a.currency() == null ? n.getCurrency() : a.currency());

        // Idempotency: a repeat call with the same (transactionRef, action) returns the
        // original confirmed booking without applying the delta a second time. This is
        // the contract the disbursement-release path relies on to be retry-safe — if
        // anything after the limit call fails locally, the next release attempt is a
        // no-op here instead of a double-book.
        if (a.transactionRef() != null && !a.transactionRef().isBlank()) {
            Utilisation existing = ledger
                    .findFirstByTransactionRefAndActionAndStatus(a.transactionRef(), action, "CONFIRMED")
                    .orElse(null);
            if (existing != null) {
                return new ActionResult(a.lineId(), action, true,
                        "Idempotent — existing booking (txnRef " + a.transactionRef() + ")",
                        round(Money.asDouble(n.getOutstanding())), round(Money.asDouble(n.available())));
            }
        }

        if ("UTILISE".equals(action) || "RESERVE".equals(action)) {
            // Hard stops that override cannot bypass.
            if (!"ACTIVE".equals(n.getStatus())) {
                return reject(n, a, amtBase, pp, "Line is " + n.getStatus());
            }
            if (n.getExpiryDate() != null && n.getExpiryDate().isBefore(LocalDate.now())) {
                return reject(n, a, amtBase, pp, "Line expired");
            }
            ValidationResult v = validate(cif, a.lineId(), a.amount(), a.currency(), null);
            if (!v.success()) {
                if (!override) {
                    return reject(n, a, amtBase, pp, v.message() + " — " +
                            v.checks().stream().filter(c -> !c.pass()).map(ValidationCheck::name).toList());
                }
                // G4: override is being EXERCISED to bypass a real failure — require an actor holding a
                // limit-override role (ACTOR_ROLE master). Capability gate, orthogonal to name-equality
                // SoD. Directory cold-outage => fail-open + WARN (ActorDirectory); unauthorised => 403.
                roles.require(actor, ProtectedAction.LIMIT_OVERRIDE);
            }
        }

        boolean overrideApplied = false;
        double countryDelta = 0;
        switch (action) {
            case "UTILISE" -> {
                ValidationResult v = validate(cif, a.lineId(), a.amount(), a.currency(), null);
                overrideApplied = !v.success() && override;
                adjust(n, amtBase, amtBase, 0);
                countryDelta = amtBase;
            }
            case "RELEASE" -> { adjust(n, -amtBase, n.isRevolving() ? 0 : 0, 0); countryDelta = -amtBase; }   // release reduces outstanding; non-revolving keeps cumulativeDrawn
            case "RESERVE" -> adjust(n, 0, 0, amtBase);
            case "RELEASE_RESERVE" -> adjust(n, 0, 0, -amtBase);   // cancels an earmark; restores headroom (result floored >= 0)
            case "REVERSAL" -> { adjust(n, -amtBase, -amtBase, 0); countryDelta = -amtBase; }
            default -> {
                return new ActionResult(a.lineId(), action, false, "Unknown action", round(Money.asDouble(n.getOutstanding())), round(Money.asDouble(n.available())));
            }
        }
        accrueCountry(n, countryDelta);
        Utilisation u = new Utilisation();
        u.setLimitNodeId(n.getId());
        u.setCif(n.getCif());
        u.setAction(action);
        u.setAmount(Money.of(a.amount()));
        u.setCurrency(a.currency() == null ? n.getCurrency() : a.currency());
        u.setBaseAmount(Money.of(amtBase));
        u.setProductProcessor(pp);
        u.setTransactionRef(a.transactionRef());
        u.setOverrideApplied(overrideApplied);
        u.setStatus("CONFIRMED");
        ledger.save(u);
        audit.engine("LIMIT_" + action, "Limit", n.getReference(),
                "%s %.0f %s on %s%s".formatted(action, a.amount(), u.getCurrency(), n.getReference(),
                        overrideApplied ? " (OVERRIDE)" : ""),
                Map.of("action", action, "baseAmount", amtBase, "override", overrideApplied, "pp", String.valueOf(pp)));
        return new ActionResult(a.lineId(), action, true,
                overrideApplied ? "Confirmed (override applied)" : "Confirmed",
                round(Money.asDouble(n.getOutstanding())), round(Money.asDouble(n.available())));
    }

    private ActionResult reject(LimitNode n, UtilisationAction a, double amtBase, String pp, String reason) {
        Utilisation u = new Utilisation();
        u.setLimitNodeId(n.getId());
        u.setCif(n.getCif());
        u.setAction(a.action().toUpperCase());
        u.setAmount(Money.of(a.amount()));
        u.setCurrency(a.currency() == null ? n.getCurrency() : a.currency());
        u.setBaseAmount(Money.of(amtBase));
        u.setProductProcessor(pp);
        u.setTransactionRef(a.transactionRef());
        u.setStatus("REJECTED");
        u.setMessage(reason);
        ledger.save(u);
        return new ActionResult(a.lineId(), a.action(), false, reason, round(Money.asDouble(n.getOutstanding())), round(Money.asDouble(n.available())));
    }

    /** Applies a delta to the node and rolls it up the ancestor chain to the root. */
    private void adjust(LimitNode n, double outstandingDelta, double drawnDelta, double reservedDelta) {
        // Deltas may be negative (RELEASE / REVERSAL / RELEASE_RESERVE). Floor the RESULT
        // at >= 0 via Money.nonNegative below — never the delta — so REVERSAL actually
        // decrements cumulativeDrawn, symmetric with the EOD replay (REVERSAL: drawn -= amt).
        BigDecimal odelta = Money.of(outstandingDelta);
        BigDecimal ddelta = Money.of(drawnDelta);
        BigDecimal rdelta = Money.of(reservedDelta);
        LimitNode cur = n;
        while (cur != null) {
            cur.setOutstanding(Money.nonNegative(Money.add(cur.getOutstanding(), odelta)));
            cur.setCumulativeDrawn(Money.nonNegative(Money.add(cur.getCumulativeDrawn(), ddelta)));
            cur.setReserved(Money.nonNegative(Money.add(cur.getReserved(), rdelta)));
            nodes.save(cur);
            cur = cur.getParentId() == null ? null : nodes.findById(cur.getParentId()).orElse(null);
        }
    }

    private LimitNode root(LimitNode n) {
        return n.getRootId() == null ? n : nodes.findById(n.getRootId()).orElse(n);
    }

    /** Normalise the obligor root's country ("IN-RBI" jurisdiction, or plain "IN") to the CountryLimit key ("IN"). */
    static String countryKey(String raw) {
        if (raw == null || raw.isBlank()) return null;
        int dash = raw.indexOf('-');
        return (dash > 0 ? raw.substring(0, dash) : raw).trim().toUpperCase();
    }

    /** D8: accrue/release the drawn base amount against the obligor's country cap; no-op when none configured. */
    private void accrueCountry(LimitNode drawnNode, double delta) {
        if (delta == 0) return;
        String ck = countryKey(root(drawnNode).getCountry());
        if (ck == null) return;
        countryLimits.findByCountry(ck).ifPresent(cl -> {
            cl.setOutstanding(Math.max(0d, cl.getOutstanding() + delta));
            countryLimits.save(cl);
        });
    }

    /** G4: override review queue — confirmed utilisations where an override was exercised. */
    @Transactional(readOnly = true)
    public List<Utilisation> overridesApplied(String cif) {
        return (cif == null || cif.isBlank())
                ? ledger.findByOverrideAppliedTrueOrderByCreatedAtDesc()
                : ledger.findByCifAndOverrideAppliedTrueOrderByCreatedAtDesc(cif);
    }

    @Transactional(readOnly = true)
    public List<Utilisation> ledgerFor(String cif) {
        return ledger.findByCifOrderByCreatedAtDesc(cif);
    }

    private static double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
