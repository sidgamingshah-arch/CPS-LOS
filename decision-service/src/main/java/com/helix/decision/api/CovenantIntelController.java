package com.helix.decision.api;

import com.helix.decision.dto.CovenantIntelDtos.AssessCertificateRequest;
import com.helix.decision.dto.CovenantIntelDtos.ConfirmExtractionRequest;
import com.helix.decision.dto.CovenantIntelDtos.ExtractCovenantsRequest;
import com.helix.decision.dto.CovenantIntelDtos.ReviewRequest;
import com.helix.decision.entity.CertificateAssessment;
import com.helix.decision.entity.Covenant;
import com.helix.decision.entity.CovenantExtraction;
import com.helix.decision.service.CovenantIntelligenceService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * AI-assisted covenant intelligence — advisory extraction from CP free text and
 * compliance-certificate assessment, each with a human-confirm gate. No
 * authoritative figure is mutated by these endpoints.
 */
@RestController
@RequestMapping("/api/covenants/intel")
public class CovenantIntelController {

    private final CovenantIntelligenceService intel;

    public CovenantIntelController(CovenantIntelligenceService intel) {
        this.intel = intel;
    }

    // ---- extraction from credit-proposal free text ----

    @PostMapping("/{reference}/extract")
    public List<CovenantExtraction> extract(@PathVariable String reference,
                                            @Valid @RequestBody ExtractCovenantsRequest req,
                                            @RequestHeader(value = "X-Actor", defaultValue = "analyst.user") String actor) {
        return intel.extract(reference, req.text(), actor);
    }

    @GetMapping("/{reference}/extractions")
    public List<CovenantExtraction> extractions(@PathVariable String reference) {
        return intel.listExtractions(reference);
    }

    @PostMapping("/extractions/{id}/confirm")
    public Covenant confirmExtraction(@PathVariable Long id,
                                      @RequestBody(required = false) ConfirmExtractionRequest req,
                                      @RequestHeader(value = "X-Actor", defaultValue = "analyst.user") String actor) {
        return intel.confirmExtraction(id, req, actor);
    }

    @PostMapping("/extractions/{id}/reject")
    public CovenantExtraction rejectExtraction(@PathVariable Long id,
                                               @RequestBody(required = false) ReviewRequest req,
                                               @RequestHeader(value = "X-Actor", defaultValue = "analyst.user") String actor) {
        return intel.rejectExtraction(id, req == null ? null : req.note(), actor);
    }

    // ---- compliance-certificate assessment ----

    @PostMapping("/{reference}/certificate/assess")
    public List<CertificateAssessment> assess(@PathVariable String reference,
                                              @Valid @RequestBody AssessCertificateRequest req,
                                              @RequestHeader(value = "X-Actor", defaultValue = "analyst.user") String actor) {
        return intel.assessCertificate(reference, req.text(), actor);
    }

    @GetMapping("/{reference}/certificate/assessments")
    public List<CertificateAssessment> assessments(@PathVariable String reference) {
        return intel.listAssessments(reference);
    }

    @PostMapping("/certificate/assessments/{id}/confirm")
    public CertificateAssessment confirmAssessment(@PathVariable Long id,
                                                   @RequestBody(required = false) ReviewRequest req,
                                                   @RequestHeader(value = "X-Actor", defaultValue = "analyst.user") String actor) {
        return intel.reviewAssessment(id, true, req == null ? null : req.note(), actor);
    }

    @PostMapping("/certificate/assessments/{id}/reject")
    public CertificateAssessment rejectAssessment(@PathVariable Long id,
                                                  @RequestBody(required = false) ReviewRequest req,
                                                  @RequestHeader(value = "X-Actor", defaultValue = "analyst.user") String actor) {
        return intel.reviewAssessment(id, false, req == null ? null : req.note(), actor);
    }
}
