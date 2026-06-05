package com.helix.limit.service;

import com.helix.common.audit.AuditService;
import com.helix.common.web.ApiException;
import com.helix.limit.client.LimitUpstreamClient;
import com.helix.limit.client.LimitUpstreamClient.CreditInputsDto;
import com.helix.limit.client.LimitUpstreamClient.FacilityDto;
import com.helix.limit.dto.Dtos.AddChildRequest;
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
        n.setSanctionedAmount(req.sanctionedAmount());
        n.setCurrency(req.currency());
        n.setBaseAmount(fx.toBase(req.sanctionedAmount(), req.currency()));
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
        double allocated = siblings.stream().mapToDouble(LimitNode::getBaseAmount).sum();
        if (allocated + childBase > parent.getBaseAmount() + 1e-6) {
            throw ApiException.badRequest(
                    "Sub-limit breaches parent cap: allocated %.0f + new %.0f > parent %.0f (base)"
                            .formatted(allocated, childBase, parent.getBaseAmount()));
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
        n.setSanctionedAmount(req.sanctionedAmount());
        n.setCurrency(req.currency());
        n.setBaseAmount(childBase);
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
        double total = facilities.stream().mapToDouble(FacilityDto::amount).sum();
        String ccy = ci.currency() == null ? facilities.get(0).currency() : ci.currency();

        LimitNode root = new LimitNode();
        root.setReference(ref());
        root.setCif(ci.counterpartyRef());
        root.setApplicationRef(applicationRef);
        root.setLevel(0);
        root.setCode("OBLIGOR");
        root.setSanctionedAmount(total);
        root.setCurrency(ccy);
        root.setBaseAmount(fx.toBase(total, ccy));
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
        n.setSanctionedAmount(amount);
        n.setCurrency(currency);
        n.setBaseAmount(fx.toBase(amount, currency));
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
        double sanctioned = all.stream().filter(n -> n.getLevel() == 0).mapToDouble(LimitNode::getBaseAmount).sum();
        double outstanding = all.stream().filter(n -> n.getLevel() == 0).mapToDouble(LimitNode::getOutstanding).sum();
        double available = all.stream().filter(n -> n.getLevel() == 0).mapToDouble(LimitNode::available).sum();

        Map<String, List<LimitNode>> groups = new LinkedHashMap<>();
        for (LimitNode n : all) {
            if (n.isFungible() && n.getInterchangeableGroup() != null) {
                groups.computeIfAbsent(n.getInterchangeableGroup(), k -> new ArrayList<>()).add(n);
            }
        }
        List<RollupGroup> groupViews = groups.entrySet().stream().map(e -> new RollupGroup(
                e.getKey(),
                round(e.getValue().stream().mapToDouble(LimitNode::getBaseAmount).sum()),
                round(e.getValue().stream().mapToDouble(LimitNode::getOutstanding).sum()),
                e.getValue().stream().map(LimitNode::getCode).toList())).toList();

        return new TreeView(cif, FxService.BASE, round(sanctioned), round(outstanding), round(available),
                views, groupViews);
    }

    @Transactional(readOnly = true)
    public NodeView node(String reference) {
        return view(byRef(reference));
    }

    private NodeView view(LimitNode n) {
        return new NodeView(n.getId(), n.getReference(), n.getParentId(), n.getRootId(), n.getLevel(),
                n.getCode(), n.getProductType(), n.isRevolving(), n.getSanctionedAmount(), n.getCurrency(),
                round(n.getBaseAmount()), round(n.getOutstanding()), round(n.getReserved()), round(n.available()),
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

        double bookTotal = nodes.findByLevel(0).stream().mapToDouble(LimitNode::getBaseAmount).sum();
        double sectorTotal = nodes.findByLevel(0).stream()
                .filter(n -> root.getSector() != null && root.getSector().equals(n.getSector()))
                .mapToDouble(LimitNode::getBaseAmount).sum();

        double projectedSingle = root.getOutstanding() + incrementalBase;
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
