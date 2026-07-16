package com.helix.decision.api;

import com.helix.decision.service.PrintService;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * Print / PDF rendering endpoints for the credit artifacts. Each endpoint returns a
 * self-contained, print-optimised standalone HTML document (letterhead, governance
 * header, {@code @media print} styling) whose body reproduces the authoritative
 * artifact verbatim. The frontend opens the returned page and invokes the browser's
 * print-to-PDF — a robust, dependency-free "PDF rendering" path.
 *
 * <p>Additive and read-only with respect to the artifact: the source generated
 * document / credit proposal is never mutated (see {@link PrintService}); the only
 * side effect is an append-only {@code *_RENDERED} audit event carrying the X-Actor.
 * Mappings are distinct from the existing {@code /api/docs} and {@code /api/decisions}
 * handlers, so nothing already wired is changed.
 */
@RestController
public class PrintController {

    private final PrintService print;

    public PrintController(PrintService print) {
        this.print = print;
    }

    /** Print/PDF view for a generated document (facility letter, sanction letter, …). */
    @GetMapping(value = "/api/docs/{id}/print", produces = MediaType.TEXT_HTML_VALUE)
    public String document(@PathVariable Long id,
                           @RequestHeader(value = "X-Actor", defaultValue = "system") String actor,
                           HttpServletResponse response) {
        // A rendered credit artifact carries authoritative (and possibly sensitive) figures —
        // it must never be persisted by a shared/intermediary cache or the browser's disk cache.
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        return print.renderDocument(id, actor);
    }

    /** Print/PDF view for the latest credit proposal on an application. */
    @GetMapping(value = "/api/decisions/{reference}/credit-proposal/print", produces = MediaType.TEXT_HTML_VALUE)
    public String creditProposal(@PathVariable String reference,
                                 @RequestHeader(value = "X-Actor", defaultValue = "system") String actor,
                                 HttpServletResponse response) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        return print.renderProposal(reference, actor);
    }
}
