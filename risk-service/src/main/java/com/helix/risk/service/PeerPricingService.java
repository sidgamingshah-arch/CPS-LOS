package com.helix.risk.service;

import com.helix.risk.client.RiskMasterClient;
import com.helix.risk.client.RiskMasterClient.MasterRecordDto;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Deterministic PEER / BENCHMARK pricing lookup — the second of the dual-approach prices.
 *
 * <p>Reads the {@code PEER_PRICING} master (config-service) keyed by segment / rating / product and
 * resolves a benchmark all-in rate (or a spread over the cost of funds). This is a plain master
 * value, NOT a model output — no GenAI is involved; it is presented alongside the deterministic
 * RAROC price so a human sees both, and is never silently blended into the authoritative figure.
 *
 * <p>Fail-soft: if the master is absent / unreachable / has no matching record, the resolution
 * reports {@code available=false} and the pricing view simply shows only the RAROC price.
 */
@Service
public class PeerPricingService {

    private final RiskMasterClient masters;

    public PeerPricingService(RiskMasterClient masters) {
        this.masters = masters;
    }

    /**
     * Resolve the peer/benchmark price for a deal segment/grade/product.
     *
     * @param costOfFunds the deal's resolved cost of funds — used when the record carries a spread
     *                    over funding rather than an absolute all-in rate.
     * @return a JSON-ready block: {@code available} + (when available) {@code peerRate},
     *         {@code matchedKey}, {@code spreadBps}/{@code allInRateBps}, {@code source}.
     */
    public Map<String, Object> resolve(String segment, String grade, String facilityType, double costOfFunds) {
        List<MasterRecordDto> all = masters.listActive("PEER_PRICING");
        Map<String, Object> out = new LinkedHashMap<>();
        if (all.isEmpty()) {
            out.put("available", false);
            out.put("reason", "no PEER_PRICING master configured");
            return out;
        }
        Map<String, MasterRecordDto> byKey = new LinkedHashMap<>();
        for (MasterRecordDto m : all) {
            if (m.recordKey() != null) {
                byKey.put(m.recordKey().toUpperCase(), m);
            }
        }
        String seg = up(segment);
        String gr = up(grade);
        String ft = up(facilityType);
        // Most-specific match first, degrading to the broadest 'default' record.
        for (String key : List.of(
                seg + ":" + gr + ":" + ft,
                seg + ":" + gr,
                seg + ":" + ft,
                seg,
                "DEFAULT")) {
            MasterRecordDto m = byKey.get(key);
            if (m == null || m.payload() == null) {
                continue;
            }
            Double allInBps = num(m.payload().get("allInRateBps"));
            Double spreadBps = num(m.payload().get("spreadBps"));
            Double peerRate;
            if (allInBps != null) {
                peerRate = allInBps / 10_000.0;
            } else if (spreadBps != null) {
                peerRate = costOfFunds + spreadBps / 10_000.0;
            } else {
                continue;   // record carries neither an all-in rate nor a spread — skip
            }
            out.put("available", true);
            out.put("matchedKey", m.recordKey());
            out.put("peerRate", round6(peerRate));
            if (allInBps != null) {
                out.put("allInRateBps", allInBps);
            }
            if (spreadBps != null) {
                out.put("spreadBps", spreadBps);
            }
            out.put("costOfFunds", round6(costOfFunds));
            out.put("source", m.payload().get("source") == null ? "" : String.valueOf(m.payload().get("source")));
            out.put("basis", allInBps != null ? "ALL_IN_RATE" : "SPREAD_OVER_COST_OF_FUNDS");
            return out;
        }
        out.put("available", false);
        out.put("reason", "no PEER_PRICING record matched segment/grade/product");
        return out;
    }

    private static String up(String s) {
        return s == null ? "" : s.toUpperCase();
    }

    private static Double num(Object o) {
        if (o instanceof Number n) {
            return n.doubleValue();
        }
        if (o instanceof String s) {
            try {
                return Double.parseDouble(s);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private static double round6(double v) {
        return Math.round(v * 1_000_000.0) / 1_000_000.0;
    }
}
