package com.helix.origination.api;

import com.helix.origination.dto.DocIntelDtos.ConfirmRequest;
import com.helix.origination.dto.DocIntelDtos.DocCheckResponse;
import com.helix.origination.dto.DocIntelDtos.NormaliseRequest;
import com.helix.origination.dto.DocIntelDtos.NormaliseResponse;
import com.helix.origination.dto.DocIntelDtos.RejectRequest;
import com.helix.origination.dto.DocIntelDtos.TranslateRequest;
import com.helix.origination.dto.DocIntelDtos.TranslateResponse;
import com.helix.origination.entity.DocExtraction;
import com.helix.origination.service.DocIntelligenceService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * GenAI document intelligence — multilingual extraction (suggest → human confirm),
 * casual→legal / legal→plain language normalisation, translation, and document
 * checks. Every output is an audited AI suggestion; none touches the figure path.
 */
@RestController
@RequestMapping("/api/doc-intel")
public class DocIntelligenceController {

    private final DocIntelligenceService docIntel;

    public DocIntelligenceController(DocIntelligenceService docIntel) {
        this.docIntel = docIntel;
    }

    @PostMapping("/documents/{docId}/extract")
    public DocExtraction extract(@PathVariable Long docId,
                                 @RequestHeader(value = "X-Actor", defaultValue = "doc.intel") String actor) {
        return docIntel.extract(docId, actor);
    }

    @GetMapping("/documents/{docId}/extractions")
    public List<DocExtraction> extractions(@PathVariable Long docId) {
        return docIntel.extractionsFor(docId);
    }

    @PostMapping("/extractions/{id}/confirm")
    public DocExtraction confirm(@PathVariable Long id, @RequestBody(required = false) ConfirmRequest req,
                                 @RequestHeader(value = "X-Actor", defaultValue = "analyst.user") String actor) {
        DocExtraction confirmed = docIntel.confirm(id, req == null ? null : req.note(), actor);
        // AI LARGER ROLE: auto-draft the spread AFTER confirm() has committed (top-level, own
        // transaction on a free connection — no pool-1 deadlock). Advisory + fail-soft; never
        // affects the confirm response.
        docIntel.autoDraftAfterConfirm(id, actor);
        return confirmed;
    }

    @PostMapping("/extractions/{id}/reject")
    public DocExtraction reject(@PathVariable Long id, @RequestBody(required = false) RejectRequest req,
                                @RequestHeader(value = "X-Actor", defaultValue = "analyst.user") String actor) {
        return docIntel.reject(id, req == null ? "rejected" : req.reason(), actor);
    }

    @PostMapping("/normalise-language")
    public NormaliseResponse normalise(@RequestBody NormaliseRequest req,
                                       @RequestHeader(value = "X-Actor", defaultValue = "analyst.user") String actor) {
        return docIntel.normalise(req.text(), req.target(), actor);
    }

    @PostMapping("/translate")
    public TranslateResponse translate(@RequestBody TranslateRequest req,
                                       @RequestHeader(value = "X-Actor", defaultValue = "analyst.user") String actor) {
        return docIntel.translate(req.text(), req.targetLanguage(), actor);
    }

    @GetMapping("/documents/{docId}/checks")
    public DocCheckResponse checks(@PathVariable Long docId,
                                   @RequestHeader(value = "X-Actor", defaultValue = "doc.intel") String actor) {
        return docIntel.checks(docId, actor);
    }
}
