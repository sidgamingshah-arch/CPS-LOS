package com.helix.risk.api;

import com.helix.risk.dto.ProjectionDtos.DriverOverrideRequest;
import com.helix.risk.dto.ProjectionDtos.ProjectionView;
import com.helix.risk.dto.ProjectionDtos.SensitivityRequest;
import com.helix.risk.dto.ProjectionDtos.SensitivityView;
import com.helix.risk.service.ProjectionService;
import com.helix.common.web.ApiException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Multi-year financial projection API — proforma P&L / cashflow / debt-service /
 * projected DSCR from the deal's base-year actuals × analyst drivers × the resolved
 * PROJECTION_TEMPLATE. Deterministic + advisory; never moves authoritative figures.
 */
@RestController
@RequestMapping("/api/risk")
public class ProjectionController {

    private final ProjectionService projections;

    public ProjectionController(ProjectionService projections) {
        this.projections = projections;
    }

    @GetMapping("/{reference}/projection")
    public ProjectionView view(@PathVariable String reference) {
        return projections.view(reference);
    }

    @PostMapping("/{reference}/projection/drivers")
    public ProjectionView setDrivers(@PathVariable String reference,
                                     @RequestBody DriverOverrideRequest req) {
        return projections.setDrivers(reference, req == null ? null : req.drivers());
    }

    @PostMapping("/{reference}/projection/sensitivity")
    public SensitivityView sensitivity(@PathVariable String reference,
                                       @RequestBody SensitivityRequest req) {
        if (req == null || req.driver() == null || req.delta() == null) {
            throw ApiException.badRequest("driver and delta are required");
        }
        return projections.sensitivity(reference, req.driver(), req.delta());
    }

    @PostMapping("/{reference}/projection/confirm")
    public ProjectionView confirm(@PathVariable String reference,
                                  @RequestHeader("X-Actor") String actor) {
        return projections.confirm(reference, actor);
    }
}
