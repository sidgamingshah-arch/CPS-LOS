package com.helix.config.service;

import com.helix.config.entity.MasterRecord;
import com.helix.config.repo.MasterRecordRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * Resolves the most-specific ACTIVE {@code MODEL_DEFINITION} for a
 * (jurisdiction, sector, segment) triple. A model definition's {@code selector}
 * may pin any of those three; a null/absent selector field is a wildcard. The
 * candidate whose pinned fields all match AND that pins the most fields wins
 * (exact &gt; sector/segment-specific &gt; default); ties break on highest version.
 *
 * <p>This is the one piece the generic master engine doesn't give us for free —
 * masters resolve by jurisdiction only, and a scoring model is additionally
 * sector- and segment-specific.</p>
 */
@Service
public class ModelResolveService {

    private final MasterRecordRepository masters;

    public ModelResolveService(MasterRecordRepository masters) {
        this.masters = masters;
    }

    @Transactional(readOnly = true)
    public MasterRecord resolve(String jurisdiction, String sector, String segment, String modelKey) {
        return resolve("MODEL_DEFINITION", jurisdiction, sector, segment, modelKey);
    }

    /**
     * Generic most-specific resolver for ANY selector-bearing master type (MODEL_DEFINITION,
     * FINANCIAL_TEMPLATE, PROJECTION_TEMPLATE, …). A definition's {@code selector} may pin
     * jurisdiction/sector/segment; a null/blank field is a wildcard. The candidate whose
     * pinned fields all match AND that pins the most fields wins; ties break on highest version.
     */
    @Transactional(readOnly = true)
    @SuppressWarnings("unchecked")
    public MasterRecord resolve(String masterType, String jurisdiction, String sector,
                                 String segment, String modelKey) {
        List<MasterRecord> active = masters.findByMasterTypeAndStatusOrderByRecordKeyAsc(
                masterType, "ACTIVE");
        MasterRecord best = null;
        int bestSpecificity = -1;
        for (MasterRecord m : active) {
            Map<String, Object> payload = m.getPayload();
            if (payload == null) continue;
            if (modelKey != null && !modelKey.isBlank()
                    && !modelKey.equalsIgnoreCase(String.valueOf(payload.get("modelKey")))
                    && !modelKey.equalsIgnoreCase(String.valueOf(payload.get("templateKey")))
                    && !modelKey.equalsIgnoreCase(m.getRecordKey())) {
                continue;
            }
            Object selRaw = payload.get("selector");
            Map<String, Object> sel = selRaw instanceof Map ? (Map<String, Object>) selRaw : Map.of();
            int specificity = 0;
            boolean ok = true;
            // jurisdiction can come from the selector OR the master's own jurisdiction column.
            // Specificity is counted on the BLANK-NORMALISED value (str maps ""/blank -> null),
            // consistent with matches(): a selector field of "" is a wildcard and must NOT
            // out-rank a true default {}.
            String selJur = str(sel.get("jurisdiction"), m.getJurisdiction());
            if (!matches(selJur, jurisdiction)) { ok = false; } else if (selJur != null) specificity++;
            String selSec = str(sel.get("sector"), null);
            if (!matches(selSec, sector)) { ok = false; } else if (selSec != null) specificity++;
            String selSeg = str(sel.get("segment"), null);
            if (!matches(selSeg, segment)) { ok = false; } else if (selSeg != null) specificity++;
            if (!ok) continue;
            if (specificity > bestSpecificity
                    || (specificity == bestSpecificity && best != null && m.getVersion() > best.getVersion())) {
                best = m;
                bestSpecificity = specificity;
            }
        }
        return best;
    }

    /** A null/blank selector field is a wildcard; otherwise it must equal the query (case-insensitive). */
    private static boolean matches(String selectorValue, String query) {
        if (selectorValue == null || selectorValue.isBlank()) return true;   // wildcard
        return query != null && selectorValue.equalsIgnoreCase(query);
    }

    private static String str(Object o, String dflt) {
        if (o == null) return dflt;
        String s = String.valueOf(o);
        return s.isBlank() ? dflt : s;
    }
}
