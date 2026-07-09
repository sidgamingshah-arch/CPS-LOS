package com.helix.config.api;

import com.helix.common.web.ApiException;
import com.helix.config.entity.MasterRecord;
import com.helix.config.service.MasterDataService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Public read-only resolver for the generic CODE_VALUE master — the source of
 * truth for every UI dropdown ("code value" reference data) in the platform.
 * Authoring + maker-checker happen through the existing master engine
 * ({@code /api/masters/CODE_VALUE}); this endpoint just hands the active values
 * to the frontend in a flat, render-ready shape, sorted by {@code sortOrder}.
 */
@RestController
@RequestMapping("/api/code-values")
public class CodeValueController {

    private final MasterDataService masters;

    public CodeValueController(MasterDataService masters) {
        this.masters = masters;
    }

    public record CodeValue(String code, String label, Double score, Integer sortOrder) { }

    public record CodeValueSet(String domain, String label, List<CodeValue> values) { }

    @GetMapping("/{domain}")
    public CodeValueSet get(@PathVariable String domain) {
        // MasterDataService.active(...) throws 404 itself when no active record exists.
        MasterRecord rec = masters.active("CODE_VALUE", domain);
        if (rec.getPayload() == null) {
            throw ApiException.notFound("No active CODE_VALUE payload for '" + domain + "'");
        }
        return parse(domain, rec.getPayload());
    }

    /** All active domains in one call — used by the frontend on app boot. */
    @GetMapping
    public List<CodeValueSet> all() {
        List<CodeValueSet> out = new ArrayList<>();
        for (MasterRecord r : masters.listActive("CODE_VALUE")) {
            if (r.getPayload() == null) continue;
            out.add(parse(r.getRecordKey(), r.getPayload()));
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private CodeValueSet parse(String domain, Map<String, Object> payload) {
        Object raw = payload.get("values");
        List<CodeValue> values = new ArrayList<>();
        if (raw instanceof List<?> list) {
            for (Object o : list) {
                if (!(o instanceof Map)) continue;
                Map<String, Object> v = (Map<String, Object>) o;
                String code = v.get("code") == null ? null : String.valueOf(v.get("code"));
                if (code == null || code.isBlank()) continue;
                String label = v.get("label") == null ? code : String.valueOf(v.get("label"));
                Double score = v.get("score") instanceof Number n ? n.doubleValue() : null;
                Integer sort = v.get("sortOrder") instanceof Number n ? n.intValue() : null;
                values.add(new CodeValue(code, label, score, sort));
            }
        }
        // Preserve declared order when sortOrder is missing; otherwise sort by it.
        values.sort((a, b) -> {
            int ao = a.sortOrder() == null ? Integer.MAX_VALUE : a.sortOrder();
            int bo = b.sortOrder() == null ? Integer.MAX_VALUE : b.sortOrder();
            return Integer.compare(ao, bo);
        });
        String label = payload.get("label") == null ? domain : String.valueOf(payload.get("label"));
        return new CodeValueSet(domain, label, values);
    }
}
