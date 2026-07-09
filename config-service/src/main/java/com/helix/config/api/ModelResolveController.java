package com.helix.config.api;

import com.helix.common.web.ApiException;
import com.helix.config.entity.MasterRecord;
import com.helix.config.service.ModelResolveService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Resolve the active scoring-model definition for a deal's
 * (jurisdiction, sector, segment). Definitions themselves are authored and
 * governed through the generic master engine ({@code /api/masters/MODEL_DEFINITION},
 * maker-checker + versioning + SoD); this endpoint just picks the right one.
 */
@RestController
@RequestMapping("/api/models")
public class ModelResolveController {

    private final ModelResolveService resolver;

    public ModelResolveController(ModelResolveService resolver) {
        this.resolver = resolver;
    }

    @GetMapping("/resolve")
    public MasterRecord resolve(@RequestParam(required = false) String jurisdiction,
                                @RequestParam(required = false) String sector,
                                @RequestParam(required = false) String segment,
                                @RequestParam(required = false) String modelKey) {
        MasterRecord m = resolver.resolve(jurisdiction, sector, segment, modelKey);
        if (m == null) {
            throw ApiException.notFound("No active MODEL_DEFINITION matches jurisdiction="
                    + jurisdiction + " sector=" + sector + " segment=" + segment
                    + (modelKey == null || modelKey.isBlank() ? "" : " modelKey=" + modelKey));
        }
        return m;
    }
}
