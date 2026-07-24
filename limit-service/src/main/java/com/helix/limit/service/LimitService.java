package com.helix.limit.service;

import com.helix.common.audit.AuditService;
import com.helix.common.money.Money;
import com.helix.common.web.ApiException;
import com.helix.limit.client.LimitUpstreamClient;
import com.helix.limit.client.LimitUpstreamClient.CreditInputsDto;
import com.helix.limit.client.LimitUpstreamClient.FacilityDto;
import com.helix.limit.dto.Dtos.AddChildRequest;
import com.helix.limit.dto.Dtos.ApplicationStatusResult;
import com.helix.limit.dto.Dtos.CreateRootRequest;
import com.helix.limit.dto.Dtos.ExposureCheckResult;
import com.helix.limit.dto.Dtos.NodeView;
import com.helix.limit.dto.Dtos.RollupGroup;
import com.helix.limit.dto.Dtos.TreeView;
import com.helix.limit.dto.Dtos.ValidationCheck;
import com.helix.limit.entity.LimitNode;
import com.helix.limit.repo.LimitNodeRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Limit-tree construction, roll-up views, maintenance and exposure-norm validation. */
@Service
public class LimitService {

    private static final int MAX_LEVEL = 4;        // 5 levels: 0..4
    private static final int MAX_CHILDREN = 50;
    private static final Set<String> REVOLVING = Set.of(
            "WORKING_CAPITAL", "REVOLVING_CREDIT", "CASH_CREDIT", "OVERDRAFT", "CC", "OD",
            "LETTER_OF_CREDIT", "LC", "LC_INLAND", "LC_FOREIGN", "TRADE_LINE", "BILL_DISCOUNTING", "BD");

    private final LimitNodeRepository nodes;
    private final FxService fx;
    private final LimitUpstreamClient upstream;
    private final AuditService audit;

    public LimitService(LimitNodeRepository nodes, FxService fx, LimitUpstreamClient upstream, AuditService audit) {
        this.nodes = nodes;
        this.fx = fx;
        this.upstream = upstream;
        this.audit = audit;
    }

    static boolean isRevolving(String type) {
        if (type == null) return false;
        String t = type.toUpperCase();
        return REVOLVING.contains(t) || t.contains("REVOLV");
    }

    private static String ref() {
        return "LMT-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }

    // -------------------------------------------------------------- construction

    @Transactional
    public LimitNode createRoot(CreateRootRequest req, String actor) {
        LimitNode n = new LimitNode();
        n.setReference(ref());
        n.setCif(req.cif());
        n.setApplicationRef(req.applicationRef());
        n.setGroupId(req.groupId());
        n.setLevel(0);
        n.setCode(req.code());
        n.setRevolving(false);
        n.setSanctionedAmount(Money.of(req.sanctionedAmount()));
        n.setCurrency(req.currency());
        n.setBaseAmount(Money.of(fx.toBase(req.sanctionedAmount(), req.currency())));
        n.setTenorMonths(req.tenorMonths());
        n.setSegment(req.segment());
        n.setSector(req.sector());
        n.setCountry(req.country());
        n.setFungible(req.fungible());
        LimitNode saved = nodes.save(n);
        saved.setRootId(saved.getId());
        saved = nodes.save(saved);
        audit.human(actor, "LIMIT_ROOT_CREATED", "Limit", saved.getReference(),
                "Created obligor limit %s %.0f %s".formatted(req.code(), req.sanctionedAmount(), req.currency()),
                Map.of("cif", req.cif(), "sanctioned", req.sanctionedAmount()));
        return saved;
    }

    @Transactional
    public LimitNode addChild(Long parentId, AddChildRequest req, String actor) {
        LimitNode parent = get(parentId);
        int level = parent.getLevel() + 1;
        if (level > MAX_LEVEL) {
            throw ApiException.badRequest("Maximum limit-tree depth is %d levels".formatted(MAX_LEVEL + 1));
        }
        List<LimitNode> siblings = nodes.findByParentId(parentId);
        if (siblings.size() >= MAX_CHILDREN) {
            throw ApiException.badRequest("Maximum %d sub-limits per parent".formatted(MAX_CHILDREN));
        }
        double childBase = fx.toBase(req.sanctionedAmount(), req.currency());
        double allocated = siblings.stream().map(LimitNode::getBaseAmount).reduce(Money.ZERO, Money::add).doubleValue();
        if (allocated + childBase > Money.asDouble(parent.getBaseAmount()) + 1e-6) {
            throw ApiException.badRequest(
                    "Sub-limit breaches parent cap: allocated %.0f + new %.0f > parent %.0f (base)"
                            .formatted(allocated, childBase, Money.asDouble(parent.getBaseAmount())));
        }
        LimitNode n = new LimitNode();
        n.setReference(ref());
        n.setCif(parent.getCif());
        n.setApplicationRef(parent.getApplicationRef());
        n.setGroupId(parent.getGroupId());
        n.setParentId(parentId);
        n.setRootId(parent.getRootId());
        n.setLevel(level);
        n.setOrdinal(siblings.size());
        n.setCode(req.code());
        n.setProductType(req.productType());
        n.setClassification(req.classification());
        n.setRevolving(req.revolving());
        n.setSanctionedAmount(Money.of(req.sanctionedAmount()));
        n.setCurrency(req.currency());
        n.setBaseAmount(Money.of(childBase));
        n.setTenorMonths(req.tenorMonths() == null ? parent.getTenorMonths() : req.tenorMonths());
        n.setSeniority(req.seniority());
        n.setFungible(req.fungible());
        n.setInterchangeableGroup(req.interchangeableGroup());
        n.setSegment(parent.getSegment());
        n.setSector(parent.getSector());
        n.setCountry(parent.getCountry());
        LimitNode saved = nodes.save(n);
        audit.human(actor, "LIMIT_CHILD_ADDED", "Limit", saved.getReference(),
                "Added L%d %s %.0f %s under %s".formatted(level, req.code(), req.sanctionedAmount(),
                        req.currency(), parent.getReference()),
                Map.of("parent", parent.getReference(), "code", req.code()));
        return saved;
    }

    /** Builds a limit tree (obligor → facilities → sub-limits) from an approved deal. */
    @Transactional
    public TreeView buildFromDeal(String applicationRef, String actor) {
        CreditInputsDto ci = upstream.creditInputs(applicationRef);
        List<FacilityDto> facilities = upstream.facilities(applicationRef);
        if (facilities.isEmpty()) {
            throw ApiException.badRequest("No facilities to build a limit tree for " + applicationRef);
        }
        boolean exists = nodes.findByCifOrderByLevelAscOrdinalAsc(ci.counterpartyRef()).stream()
                .anyMatch(n -> applicationRef.equals(n.getApplicationRef()) && n.getLevel() == 0);
        if (exists) {
            throw ApiException.conflict("A limit tree already exists for " + applicationRef);
        }
        // Facility legs may each be in a different currency; the obligor root cap must be
        // the sum of each leg CONVERTED to the platform base (INR) — exactly how child()
        // computes baseAmount (fx.toBase per leg). Summing raw native amounts and converting
        // once at a single currency mis-states a multi-currency root and breaks the sibling-cap
        // and roll-up invariants. The root is denominated in base so EOD revaluation skips it
        // (its children revalue individually in their own currencies).
        double totalBase = facilities.stream()
                .mapToDouble(f -> fx.toBase(f.amount(), f.currency()))
                .sum();

        LimitNode root = new LimitNode();
        root.setReference(ref());
        root.setCif(ci.counterpartyRef());
        root.setApplicationRef(applicationRef);
        root.setLevel(0);
        root.setCode("OBLIGOR");
        root.setSanctionedAmount(Money.of(totalBase));
        root.setCurrency(FxService.BASE);
        root.setBaseAmount(Money.of(totalBase));
        root.setSegment(ci.segment());
        root.setSector(ci.segment());
        root.setCountry(ci.jurisdiction());
        root = nodes.save(root);
        root.setRootId(root.getId());
        root = nodes.save(root);

        int fOrd = 0;
        for (FacilityDto f : facilities) {
            LimitNode fac = child(root, fOrd++, f.facilityType(), f.facilityType(), null,
                    isRevolving(f.facilityType()), f.amount(), f.currency(), f.tenorMonths(), null, false, null);
            // Carry the upstream facility reference so disbursement-service can match
            // a draw to the correct limit node (multiple facilities of the same type
            // would otherwise collide on 'code').
            fac.setFacilityRef(f.reference());
            nodes.save(fac);
            int sOrd = 0;
            for (var s : f.sublimits() == null ? List.<LimitUpstreamClient.SublimitDto>of() : f.sublimits()) {
                child(fac, sOrd++, s.code(), s.productType(), null, isRevolving(s.productType()),
                        s.amount(), s.currency(), s.tenorMonths(), null, s.fungible(), s.interchangeableGroup());
            }
        }
        audit.human(actor, "LIMIT_TREE_BUILT", "Limit", root.getReference(),
                "Built limit tree for %s from %d facilities".formatted(applicationRef, facilities.size()),
                Map.of("applicationRef", applicationRef, "facilities", facilities.size()));
        return treeView(ci.counterpartyRef());
    }

    private LimitNode child(LimitNode parent, int ordinal, String code, String productType, String classification,
                            boolean revolving, double amount, String currency, Integer tenor, String seniority,
                            boolean fungible, String group) {
        LimitNode n = new LimitNode();
        n.setReference(ref());
        n.setCif(parent.getCif());
        n.setApplicationRef(parent.getApplicationRef());
        n.setParentId(parent.getId());
        n.setRootId(parent.getRootId());
        n.setLevel(parent.getLevel() + 1);
        n.setOrdinal(ordinal);
        n.setCode(code);
        n.setProductType(productType);
        n.setClassification(classification);
        n.setRevolving(revolving);
        n.setSanctionedAmount(Money.of(amount));
        n.setCurrency(currency);
        n.setBaseAmount(Money.of(fx.toBase(amount, currency)));
        n.setTenorMonths(tenor == null ? parent.getTenorMonths() : tenor);
        n.setSeniority(seniority);
        n.setFungible(fungible);
        n.setInterchangeableGroup(group);
        n.setSegment(parent.getSegment());
        n.setSector(parent.getSector());
        n.setCountry(parent.getCountry());
        return nodes.save(n);
    }

    // ---------------------------------------------------------------- views

    @Transactional(readOnly = true)
    public TreeView treeView(String cif) {
        List<LimitNode> all = nodes.findByCifOrderByLevelAscOrdinalAsc(cif);
        List<NodeView> views = all.stream().map(this::view).toList();
        double sanctioned = all.stream().filter(n -> n.getLevel() == 0).map(LimitNode::getBaseAmount).reduce(Money.ZERO, Money::add).doubleValue();
        double outstanding = all.stream().filter(n -> n.getLevel() == 0).map(LimitNode::getOutstanding).reduce(Money.ZERO, Money::add).doubleValue();
        double available = all.stream().filter(n -> n.getLevel() == 0).map(LimitNode::available).reduce(Money.ZERO, Money::add).doubleValue();

        Map<String, List<LimitNode>> groups = new LinkedHashMap<>();
        for (LimitNode n : all) {
            if (n.isFungible() && n.getInterchangeableGroup() != null) {
                groups.computeIfAbsent(n.getInterchangeableGroup(), k -> new ArrayList<>()).add(n);
            }
        }
        // Interchangeable group members SHARE one cap (utilisation moves freely within the pool), so
        // the DISPLAY combined cap is the shared cap — the MAX member base amount — NOT the sum of
        // members (summing double-counts the same shared headroom). Combined OUTSTANDING stays a sum:
        // that is real drawn usage across the members and is legitimately additive. NOTE: this changes
        // only the display roll-up; the authoritative runtime enforcement in UtilisationService
        // (pooled headroom = Σ member caps − Σ member usage) is deliberately left unchanged.
        List<RollupGroup> groupViews = groups.entrySet().stream().map(e -> new RollupGroup(
                e.getKey(),
                round(e.getValue().stream().mapToDouble(n -> n.getBaseAmount().doubleValue()).max().orElse(0.0)),
                round(e.getValue().stream().map(LimitNode::getOutstanding).reduce(Money.ZERO, Money::add).doubleValue()),
                e.getValue().stream().map(LimitNode::getCode).toList())).toList();

        return new TreeView(cif, FxService.BASE, round(sanctioned), round(outstanding), round(available),
                views, groupViews);
    }

    @Transactional(readOnly = true)
    public NodeView node(String reference) {
        return view(byRef(reference));
    }

    @Transactional(readOnly = true)
    public List<LimitNode> nodesByApplication(String applicationRef) {
        return nodes.findByApplicationRefOrderByLevelAscOrdinalAsc(applicationRef);
    }

    @Transactional(readOnly = true)
    public LimitNode nodeByFacility(String applicationRef, String facilityRef) {
        return nodes.findByApplicationRefAndFacilityRef(applicationRef, facilityRef)
                .orElseThrow(() -> ApiException.notFound(
                        "No limit node for facility " + facilityRef + " on " + applicationRef));
    }

    /**
     * Re-syncs a facility node after an APPROVED post-sanction amendment: sets the
     * node's sanctioned amount / tenor to the new ABSOLUTE values and rolls the
     * amount delta up the parent chain to the obligor root. Absolute targets make
     * a replay a no-op (delta computes to zero), so the decision-service approval
     * path can safely retry after a partial failure.
     */
    @Transactional
    public LimitNode resyncFacility(String applicationRef, String facilityRef, Double newAmount,
                                    Integer newTenorMonths, String amendmentRef, String actor) {
        LimitNode node = nodeByFacility(applicationRef, facilityRef);
        BigDecimal oldAmount = node.getSanctionedAmount();
        BigDecimal delta = newAmount == null ? Money.ZERO : Money.sub(Money.of(newAmount), oldAmount);
        if (newAmount != null && delta.signum() != 0) {
            node.setSanctionedAmount(Money.of(newAmount));
            node.setBaseAmount(Money.of(fx.toBase(newAmount, node.getCurrency())));
        }
        if (newTenorMonths != null && newTenorMonths > 0) {
            node.setTenorMonths(newTenorMonths);
        }
        nodes.save(node);
        if (delta.signum() != 0) {
            BigDecimal deltaBase = Money.of(fx.toBase(delta.doubleValue(), node.getCurrency()));
            Long pid = node.getParentId();
            while (pid != null) {
                LimitNode parent = nodes.findById(pid).orElse(null);
                if (parent == null) break;
                parent.setSanctionedAmount(Money.add(parent.getSanctionedAmount(), delta));
                parent.setBaseAmount(Money.add(parent.getBaseAmount(), deltaBase));
                nodes.save(parent);
                pid = parent.getParentId();
            }
        }
        audit.human(actor, "LIMIT_RESYNCED", "Limit", node.getReference(),
                "Facility %s re-synced after amendment %s: sanctioned %.2f -> %.2f, tenor %s".formatted(
                        facilityRef, amendmentRef == null ? "-" : amendmentRef,
                        Money.asDouble(oldAmount), Money.asDouble(node.getSanctionedAmount()),
                        newTenorMonths == null ? "unchanged" : String.valueOf(newTenorMonths)),
                Map.of("applicationRef", applicationRef, "facilityRef", facilityRef,
                        "oldAmount", Money.asDouble(oldAmount),
                        "newAmount", Money.asDouble(node.getSanctionedAmount()),
                        "amendmentRef", amendmentRef == null ? "" : amendmentRef));
        return node;
    }

    private NodeView view(LimitNode n) {
        return new NodeView(n.getId(), n.getReference(), n.getParentId(), n.getRootId(), n.getLevel(),
                n.getCode(), n.getProductType(), n.isRevolving(), Money.asDouble(n.getSanctionedAmount()), n.getCurrency(),
                round(Money.asDouble(n.getBaseAmount())), round(Money.asDouble(n.getOutstanding())), round(Money.asDouble(n.getReserved())), round(Money.asDouble(n.available())),
                n.isFungible(), n.getInterchangeableGroup(), n.getStatus(), n.getTenorMonths(),
                n.getExpiryDate() == null ? null : n.getExpiryDate().toString(), n.getSeniority());
    }

    // --------------------------------------------------------- maintenance

    @Transactional
    public LimitNode setStatus(Long id, String status, String reason, String actor) {
        LimitNode n = get(id);
        n.setStatus(status);
        audit.human(actor, "LIMIT_STATUS_CHANGED", "Limit", n.getReference(),
                "Status -> %s%s".formatted(status, reason == null ? "" : " (" + reason + ")"),
                Map.of("status", status));
        return nodes.save(n);
    }

    @Transactional
    public LimitNode extend(Long id, LocalDate expiry, String actor) {
        LimitNode n = get(id);
        n.setExpiryDate(expiry);
        audit.human(actor, "LIMIT_EXTENDED", "Limit", n.getReference(),
                "Expiry extended to " + expiry, Map.of("expiry", expiry.toString()));
        return nodes.save(n);
    }

    /**
     * Application-scoped freeze / release: drives every limit node for {@code applicationRef}
     * to {@code targetStatus} (FROZEN or ACTIVE), skipping CLOSED nodes (never resurrect a
     * closed limit) and nodes already at the target. IDEMPOTENT — a replay transitions
     * nothing. An EMPTY tree (deal not yet built) is a no-op success, not an error: the
     * caller is a governance side-effect that must never fail the already-committed credit
     * action. Emits one batch engine audit reflecting the real outcome.
     */
    @Transactional
    public ApplicationStatusResult setApplicationStatus(String applicationRef, String targetStatus,
                                                        String reason, String actor) {
        String target = targetStatus == null ? "" : targetStatus.toUpperCase();
        if (!target.equals("FROZEN") && !target.equals("ACTIVE")) {
            throw ApiException.badRequest("Application status must be FROZEN or ACTIVE, got " + targetStatus);
        }
        List<LimitNode> all = nodesByApplication(applicationRef);
        List<String> affected = new ArrayList<>();
        for (LimitNode n : all) {
            String s = n.getStatus();
            if ("CLOSED".equals(s) || target.equals(s)) {
                continue;   // never resurrect a closed limit; skip no-op transitions
            }
            n.setStatus(target);
            nodes.save(n);
            affected.add(n.getReference());
        }
        audit.engine(target.equals("FROZEN") ? "LIMIT_APP_FROZEN" : "LIMIT_APP_RELEASED",
                "Application", applicationRef,
                "%s %d/%d limit node(s) for %s%s".formatted(
                        target.equals("FROZEN") ? "Froze" : "Released",
                        affected.size(), all.size(), applicationRef,
                        reason == null || reason.isBlank() ? "" : " (" + reason + ")"),
                Map.of("applicationRef", applicationRef, "targetStatus", target,
                        "affected", affected.size(), "total", all.size()));
        return new ApplicationStatusResult(applicationRef, target, affected.size(), all.size(), affected);
    }

    // --------------------------------------------------------- exposure norms

    @Transactional(readOnly = true)
    public ExposureCheckResult exposureCheck(String cif, double incrementalBase) {
        LimitNode root = nodes.findByCifOrderByLevelAscOrdinalAsc(cif).stream()
                .filter(n -> n.getLevel() == 0).findFirst()
                .orElseThrow(() -> ApiException.notFound("No limit tree for " + cif));
        Map<String, Object> norms = upstream.exposureNorms(root.getCountry());
        double capital = num(norms, "capital_base", 50_000_000_000d);
        double singleNamePct = num(norms, "single_name_pct_capital", 0.15);
        double sectorPct = num(norms, "sector_cap_pct_portfolio", 0.20);

        double bookTotal = nodes.findByLevel(0).stream().map(LimitNode::getBaseAmount).reduce(Money.ZERO, Money::add).doubleValue();
        double sectorTotal = nodes.findByLevel(0).stream()
                .filter(n -> root.getSector() != null && root.getSector().equals(n.getSector()))
                .map(LimitNode::getBaseAmount).reduce(Money.ZERO, Money::add).doubleValue();

        double projectedSingle = Money.asDouble(root.getOutstanding()) + incrementalBase;
        List<ValidationCheck> checks = new ArrayList<>();
        checks.add(new ValidationCheck("single_name",
                projectedSingle <= singleNamePct * capital,
                "%.0f vs cap %.0f".formatted(projectedSingle, singleNamePct * capital)));
        checks.add(new ValidationCheck("sector_concentration",
                sectorTotal <= sectorPct * Math.max(bookTotal, 1),
                "%.0f vs cap %.0f".formatted(sectorTotal, sectorPct * Math.max(bookTotal, 1))));
        boolean within = checks.stream().allMatch(ValidationCheck::pass);
        return new ExposureCheckResult(within, checks, norms);
    }

    // --------------------------------------------------------------- helpers

    @Transactional(readOnly = true)
    public LimitNode get(Long id) {
        return nodes.findById(id).orElseThrow(() -> ApiException.notFound("No limit node: " + id));
    }

    @Transactional(readOnly = true)
    public LimitNode byRef(String reference) {
        return nodes.findByReference(reference).orElseThrow(() -> ApiException.notFound("No limit line: " + reference));
    }

    static double num(Map<String, Object> m, String k, double d) {
        Object v = m.get(k);
        return v instanceof Number n ? n.doubleValue() : d;
    }

    private static double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
