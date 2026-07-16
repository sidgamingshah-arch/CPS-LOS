package com.helix.counterparty.service;

import com.helix.common.audit.AuditService;
import com.helix.common.llm.LlmClient;
import com.helix.common.llm.LlmRequest;
import com.helix.common.llm.LlmResult;
import com.helix.counterparty.dto.Dtos.UboStructureRequest;
import com.helix.counterparty.entity.UboEdge;
import com.helix.counterparty.entity.UboNode;
import com.helix.counterparty.repo.UboEdgeRepository;
import com.helix.counterparty.repo.UboNodeRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Resolves a declared ownership structure into a beneficial-ownership graph and
 * computes each natural person's effective ownership of the counterparty by
 * summing the product of edge percentages along every path (PRD §1, US-1.1).
 * Persons at/above the UBO threshold are flagged; circular references and
 * low-confidence nodes are surfaced for human review — never silently dropped.
 */
@Service
public class UboService {

    /** From the active CDD rule pack (rbi_kyc_md_tiers: ubo_threshold_pct = 10%). */
    private static final double UBO_THRESHOLD = 0.10;
    private static final double REVIEW_CONFIDENCE = 0.80;

    private final UboNodeRepository nodes;
    private final UboEdgeRepository edges;
    private final AuditService audit;
    private final LlmClient llm;

    public UboService(UboNodeRepository nodes, UboEdgeRepository edges, AuditService audit, LlmClient llm) {
        this.nodes = nodes;
        this.edges = edges;
        this.audit = audit;
        this.llm = llm;
    }

    @Transactional
    public List<UboNode> resolve(Long counterpartyId, String reference, UboStructureRequest req, String actor) {
        nodes.deleteByCounterpartyId(counterpartyId);
        edges.deleteByCounterpartyId(counterpartyId);

        // Persist edges (parent owns child).
        Map<String, List<UboEdge>> outgoing = new HashMap<>();
        for (var e : req.edges()) {
            UboEdge edge = new UboEdge();
            edge.setCounterpartyId(counterpartyId);
            edge.setParentKey(e.parent());
            edge.setChildKey(e.child());
            edge.setOwnershipPct(e.ownershipPct());
            edges.save(edge);
            outgoing.computeIfAbsent(e.parent(), k -> new ArrayList<>()).add(edge);
        }

        boolean hasCycle = detectCycle(req);

        // The counterparty itself is the ROOT (the entity being owned).
        String rootKey = req.nodes().stream()
                .filter(n -> "ROOT".equalsIgnoreCase(n.type()))
                .map(UboStructureRequest.NodeInput::key)
                .findFirst()
                .orElse(req.nodes().isEmpty() ? null : req.nodes().get(0).key());

        List<UboNode> result = new ArrayList<>();
        int flaggedUbos = 0;
        // Per-UBO facts for the advisory narrative — keyed by node key, never the (PII) name.
        List<String> uboFacts = new ArrayList<>();
        for (var n : req.nodes()) {
            UboNode node = new UboNode();
            node.setCounterpartyId(counterpartyId);
            node.setNodeKey(n.key());
            node.setName(n.name());
            node.setNodeType(n.type());
            node.setCountry(n.country());
            node.setConfidence(n.confidence() == null ? 1.0 : n.confidence());

            double effective = "PERSON".equalsIgnoreCase(n.type())
                    ? effectiveOwnership(n.key(), rootKey, outgoing, hasCycle)
                    : 0.0;
            node.setEffectiveOwnership(effective);
            boolean isUbo = "PERSON".equalsIgnoreCase(n.type()) && effective >= UBO_THRESHOLD;
            node.setUbo(isUbo);
            // Route to a reviewer when identity confidence is low or the graph has gaps.
            node.setNeedsReview(node.getConfidence() < REVIEW_CONFIDENCE || hasCycle);
            if (isUbo) {
                flaggedUbos++;
                uboFacts.add(node.getNodeKey() + "=" + String.format(Locale.UK, "%.1f%%", effective * 100));
            }
            result.add(nodes.save(node));
        }

        // Deterministic resolution summary is always the fallback (byte-identical when provider=none).
        String template = "Resolved %d nodes; %d UBO(s) >= %.0f%%%s".formatted(
                result.size(), flaggedUbos, UBO_THRESHOLD * 100,
                hasCycle ? "; CIRCULAR ownership flagged for review" : "");
        String message = template;

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("uboThreshold", UBO_THRESHOLD);
        detail.put("hasCycle", hasCycle);
        detail.put("uboCount", flaggedUbos);

        // Optional advisory LLM narrative: when a bank has configured an external model it drafts
        // the human-readable resolution explanation grounded ONLY in the deterministic facts
        // (node/UBO counts, threshold, cycle flag, per-UBO effective ownership). The ownership
        // maths, UBO flags and the graph are authoritative and untouched — only the accompanying
        // explanation text changes. Provider 'none' (default) → the deterministic message.
        LlmResult r = llmNarrative(result.size(), flaggedUbos, hasCycle, uboFacts, template);
        if (r.usable()) {
            message = r.text().strip();
            detail.put("llmDrafted", true);
            detail.put("llmModel", r.model());
        }

        audit.ai("ubo-graph-resolver", "UBO_RESOLVED", "Counterparty", reference, message, detail);
        return result;
    }

    /**
     * Advisory LLM explanation that accompanies the resolved UBO graph, grounded ONLY in the
     * deterministic resolution facts (node/UBO counts, threshold, cycle flag, per-UBO effective
     * ownership by node key). It quotes those figures verbatim and re-decides nothing — the
     * ownership percentages, UBO flags and graph are authoritative. Returns a non-usable result
     * when not configured / failed / empty so the caller keeps the deterministic message.
     */
    private LlmResult llmNarrative(int nodeCount, int uboCount, boolean hasCycle,
                                   List<String> uboFacts, String deterministic) {
        String system = "You are drafting an ADVISORY, plain-prose explanation that accompanies a resolved "
                + "beneficial-ownership (UBO) graph in a wholesale-credit KYC workflow. capability=ubo-narrative. "
                + "In 2-3 sentences, summarise the resolution outcome using ONLY the facts supplied. Reuse the node "
                + "count, UBO count, threshold and every ownership percentage verbatim — never invent or change a "
                + "value, and never re-decide who is a UBO. The ownership maths and UBO flags are authoritative and "
                + "fixed; this narrative is explanatory only and a named human reviews the graph.";
        String user = "Nodes resolved: " + nodeCount
                + "\nUBOs at/above threshold: " + uboCount
                + "\nUBO threshold: " + String.format(Locale.UK, "%.0f%%", UBO_THRESHOLD * 100)
                + "\nCircular ownership detected: " + hasCycle
                + "\nPer-UBO effective ownership (nodeKey=pct — do not change): "
                + (uboFacts.isEmpty() ? "none at/above threshold" : String.join(", ", uboFacts))
                + "\nDeterministic summary for reference (improve the wording, not the facts): " + deterministic;
        return safeComplete(LlmRequest.of("ubo-narrative", system, user));
    }

    private LlmResult safeComplete(LlmRequest req) {
        try {
            LlmResult r = llm.complete(req);
            return r == null ? LlmResult.notConfigured() : r;
        } catch (Exception e) {
            return LlmResult.failed(e.getMessage());
        }
    }

    /**
     * Effective ownership of {@code personKey} over {@code rootKey}: sum over all
     * directed paths person→…→root of the product of edge ownership percentages.
     */
    private double effectiveOwnership(String personKey, String rootKey,
                                      Map<String, List<UboEdge>> outgoing, boolean hasCycle) {
        if (rootKey == null || hasCycle) {
            return 0.0;
        }
        return walk(personKey, rootKey, outgoing, 1.0, new HashSet<>());
    }

    private double walk(String current, String rootKey, Map<String, List<UboEdge>> outgoing,
                        double acc, Set<String> visiting) {
        if (current.equals(rootKey)) {
            return acc;
        }
        if (!visiting.add(current)) {
            return 0.0; // guard against cycles during traversal
        }
        double total = 0.0;
        for (UboEdge e : outgoing.getOrDefault(current, List.of())) {
            total += walk(e.getChildKey(), rootKey, outgoing, acc * e.getOwnershipPct(), visiting);
        }
        visiting.remove(current);
        return total;
    }

    private boolean detectCycle(UboStructureRequest req) {
        Map<String, List<String>> adj = new HashMap<>();
        for (var e : req.edges()) {
            adj.computeIfAbsent(e.parent(), k -> new ArrayList<>()).add(e.child());
        }
        Set<String> visited = new HashSet<>();
        Set<String> stack = new HashSet<>();
        for (String node : adj.keySet()) {
            if (dfsCycle(node, adj, visited, stack)) {
                return true;
            }
        }
        return false;
    }

    private boolean dfsCycle(String node, Map<String, List<String>> adj, Set<String> visited, Set<String> stack) {
        if (stack.contains(node)) {
            return true;
        }
        if (!visited.add(node)) {
            return false;
        }
        stack.add(node);
        for (String next : adj.getOrDefault(node, List.of())) {
            if (dfsCycle(next, adj, visited, stack)) {
                return true;
            }
        }
        stack.remove(node);
        return false;
    }

    @Transactional(readOnly = true)
    public List<UboNode> graph(Long counterpartyId) {
        return nodes.findByCounterpartyId(counterpartyId);
    }
}
