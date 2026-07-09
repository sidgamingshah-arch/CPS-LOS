package com.helix.config.api;

import com.helix.common.web.ApiException;
import com.helix.config.entity.MasterRecord;
import com.helix.config.service.ModelResolveService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Resolve the active PROJECTION_TEMPLATE (multi-year proforma driver model) for a
 * deal's (jurisdiction, sector, segment). Templates are authored + governed via the
 * generic master engine ({@code /api/masters/PROJECTION_TEMPLATE}).
 */
@RestController
@RequestMapping("/api/projection-templates")
public class ProjectionTemplateController {

    private final ModelResolveService resolver;

    public ProjectionTemplateController(ModelResolveService resolver) {
        this.resolver = resolver;
    }

    @GetMapping("/resolve")
    public MasterRecord resolve(@RequestParam(required = false) String jurisdiction,
                                @RequestParam(required = false) String sector,
                                @RequestParam(required = false) String segment,
                                @RequestParam(required = false) String templateKey) {
        MasterRecord m = resolver.resolve("PROJECTION_TEMPLATE", jurisdiction, sector, segment, templateKey);
        if (m == null) {
            throw ApiException.notFound("No active PROJECTION_TEMPLATE matches jurisdiction="
                    + jurisdiction + " sector=" + sector + " segment=" + segment
                    + (templateKey == null || templateKey.isBlank() ? "" : " templateKey=" + templateKey));
        }
        return m;
    }
}
