package com.helix.decision.api;

import com.helix.decision.dto.DocGenDtos.AddClauseRequest;
import com.helix.decision.dto.DocGenDtos.ConfirmRequest;
import com.helix.decision.dto.DocGenDtos.EditClauseRequest;
import com.helix.decision.dto.DocGenDtos.GenerateRequest;
import com.helix.decision.entity.GeneratedDocument;
import com.helix.decision.service.DocGenService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Document generation — pulls DOC_TEMPLATE_MASTER + TNC_MASTER, generates AI-drafted
 * documents (facility letters, sanction letters, security docs) with clause add/
 * remove/edit and a human-confirm gate.
 */
@RestController
@RequestMapping("/api/docs")
public class DocGenController {

    private final DocGenService docs;

    public DocGenController(DocGenService docs) {
        this.docs = docs;
    }

    @GetMapping("/templates")
    public List<Map<String, Object>> templates() {
        return docs.listTemplates();
    }

    @GetMapping("/tnc-clauses")
    public List<Map<String, Object>> tncClauses() {
        return docs.listTncClauses();
    }

    @PostMapping("/applications/{reference}/generate")
    public GeneratedDocument generate(@PathVariable String reference, @RequestBody GenerateRequest req,
                                      @RequestHeader(value = "X-Actor", defaultValue = "cad.officer") String actor) {
        return docs.generate(reference, req.templateKey(), req.variables(), actor);
    }

    @GetMapping("/applications/{reference}")
    public List<GeneratedDocument> list(@PathVariable String reference) {
        return docs.list(reference);
    }

    @GetMapping("/{id}")
    public GeneratedDocument get(@PathVariable Long id) {
        return docs.get(id);
    }

    @PostMapping("/{id}/clauses")
    public GeneratedDocument addClause(@PathVariable Long id, @RequestBody AddClauseRequest req,
                                       @RequestHeader(value = "X-Actor", defaultValue = "cad.officer") String actor) {
        return docs.addClause(id, req, actor);
    }

    @DeleteMapping("/{id}/clauses/{clauseRef}")
    public GeneratedDocument removeClause(@PathVariable Long id, @PathVariable String clauseRef,
                                          @RequestHeader(value = "X-Actor", defaultValue = "cad.officer") String actor) {
        return docs.removeClause(id, clauseRef, actor);
    }

    @PostMapping("/{id}/clauses/{clauseRef}/edit")
    public GeneratedDocument editClause(@PathVariable Long id, @PathVariable String clauseRef,
                                        @RequestBody EditClauseRequest req,
                                        @RequestHeader(value = "X-Actor", defaultValue = "cad.officer") String actor) {
        return docs.editClause(id, clauseRef, req.text(), actor);
    }

    @PostMapping("/{id}/confirm")
    public GeneratedDocument confirm(@PathVariable Long id, @RequestBody(required = false) ConfirmRequest req,
                                     @RequestHeader(value = "X-Actor", defaultValue = "credit.officer") String actor) {
        return docs.confirm(id, req == null ? null : req.comment(), actor);
    }

    @PostMapping("/{id}/withdraw")
    public GeneratedDocument withdraw(@PathVariable Long id,
                                      @RequestHeader(value = "X-Actor", defaultValue = "credit.officer") String actor) {
        return docs.withdraw(id, actor);
    }
}
