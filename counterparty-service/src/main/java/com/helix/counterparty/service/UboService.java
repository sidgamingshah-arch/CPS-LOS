package com.helix.counterparty.service;

import com.helix.common.audit.AuditService;
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
import java.util.List;
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

    public UboService(UboNodeRepository nodes, UboEdgeRepository edges, AuditService audit) {
        this.nodes = nodes;
        this.edges = edges;
        this.audit = audit;
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
            }
            result.add(nodes.save(node));
        }

        audit.ai("ubo-graph-resolver", "UBO_RESOLVED", "Counterparty", reference,
                "Resolved %d nodes; %d UBO(s) >= %.0f%%%s".formatted(
                        result.size(), flaggedUbos, UBO_THRESHOLD * 100,
                        hasCycle ? "; CIRCULAR ownership flagged for review" : ""),
                Map.of("uboThreshold", UBO_THRESHOLD, "hasCycle", hasCycle, "uboCount", flaggedUbos));
        return result;
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
