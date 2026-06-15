package com.helix.config.api;

import com.helix.config.service.ModelDocumentService;
import com.helix.config.service.ModelDocumentService.ExtractionResult;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Upload a credit-rating model document and have its qualitative parameters + scoring
 * prompts extracted into the {@code QUAL_SCORECARD} master (the prompt library). The
 * extracted records land PENDING and flow through the normal maker-checker approval —
 * so a bank configures its qualitative scorecard by uploading its own model, then
 * reviewing/approving (and editing) the proposed parameters and prompts.
 */
@RestController
@RequestMapping("/api/model-doc")
public class ModelDocumentController {

    private final ModelDocumentService models;

    public ModelDocumentController(ModelDocumentService models) {
        this.models = models;
    }

    public record ExtractRequest(@NotBlank String text, String jurisdiction, Boolean replaceExisting) { }

    @PostMapping("/extract")
    public ExtractionResult extract(@RequestBody ExtractRequest req,
                                    @RequestHeader(value = "X-Actor", defaultValue = "config.admin") String actor) {
        return models.extract(req.text(), req.jurisdiction(),
                Boolean.TRUE.equals(req.replaceExisting()), actor);
    }
}
