package com.helix.config.api;

import com.helix.common.governance.AiCapability;
import com.helix.config.entity.MasterRecord;
import com.helix.config.service.MasterDataService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Read-only resolver for the {@code AI_GOVERNANCE} master. The full CRUD lives on
 * the generic {@link MasterController} (so flipping a capability goes through the
 * same maker-checker SoD as every other master change). This controller exists
 * because (a) the frontend needs a single call that returns the resolved state of
 * every capability for a given jurisdiction, and (b) consumers want a typed view
 * of "what AI is in this platform" without enumerating master rows themselves.
 *
 * <p>Resolution order matches {@code com.helix.common.governance.AiGovernanceClient}:
 * jurisdiction override → default (jurisdiction=null) → conservative fallback (enabled).</p>
 */
@RestController
@RequestMapping("/api/governance/ai")
public class AiGovernanceController {

    private final MasterDataService masters;

    public AiGovernanceController(MasterDataService masters) {
        this.masters = masters;
    }

    /** Catalogue of governed AI capabilities (key + description). */
    @GetMapping("/capabilities")
    public List<Map<String, String>> capabilities() {
        List<Map<String, String>> out = new ArrayList<>();
        for (AiCapability c : AiCapability.values()) {
            Map<String, String> row = new LinkedHashMap<>();
            row.put("key", c.key());
            row.put("description", c.description());
            out.add(row);
        }
        return out;
    }

    /**
     * Resolved enabled-state of every capability for the (optional) jurisdiction.
     * The returned map also includes a {@code _source} key per capability telling
     * the UI whether the value came from a jurisdiction override, the default, or
     * the conservative fallback — useful for the governance admin view.
     */
    @GetMapping("/resolved")
    public Map<String, Object> resolved(@RequestParam(required = false) String jurisdiction) {
        List<MasterRecord> all = masters.listActive("AI_GOVERNANCE");
        Map<String, Object> entries = new LinkedHashMap<>();
        for (AiCapability cap : AiCapability.values()) {
            MasterRecord override = jurisdiction == null || jurisdiction.isBlank() ? null
                    : findRecord(all, cap.key(), jurisdiction);
            MasterRecord def = findRecord(all, cap.key(), null);
            boolean enabled;
            String source;
            if (override != null) {
                enabled = readEnabled(override, true);
                source = "JURISDICTION_OVERRIDE";
            } else if (def != null) {
                enabled = readEnabled(def, true);
                source = "DEFAULT";
            } else {
                enabled = true;
                source = "FALLBACK";
            }
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("enabled", enabled);
            e.put("description", cap.description());
            e.put("source", source);
            entries.put(cap.key(), e);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("jurisdiction", jurisdiction);
        out.put("capabilities", entries);
        return out;
    }

    private static MasterRecord findRecord(List<MasterRecord> rows, String key, String jurisdiction) {
        for (MasterRecord r : rows) {
            if (!key.equals(r.getRecordKey())) continue;
            String j = r.getJurisdiction();
            if (jurisdiction == null && (j == null || j.isBlank())) return r;
            if (jurisdiction != null && jurisdiction.equals(j)) return r;
        }
        return null;
    }

    private static boolean readEnabled(MasterRecord r, boolean fallback) {
        if (r.getPayload() == null) return fallback;
        Object v = r.getPayload().get("enabled");
        if (v instanceof Boolean b) return b;
        if (v instanceof String s) return Boolean.parseBoolean(s);
        return fallback;
    }
}
