package com.helix.decision.api;

import com.helix.decision.service.PrintService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Print / office rendering endpoints for the credit artifacts. Each endpoint returns a
 * faithful rendering of the authoritative artifact in the format chosen via {@code ?format=}:
 *
 * <ul>
 *   <li>absent / {@code html} (default) — a self-contained, print-optimised standalone HTML
 *       document (letterhead, governance header, {@code @media print} styling) whose body
 *       reproduces the artifact verbatim; the frontend opens it and invokes browser
 *       print-to-PDF. <b>Byte-identical to the pre-existing behaviour.</b></li>
 *   <li>{@code rtf} — a Word-openable RTF document;</li>
 *   <li>{@code xlsx} — a SpreadsheetML 2003 XML workbook (Excel-openable, no OOXML library);</li>
 *   <li>{@code csv} — the tabular content as CSV (formula-injection guarded).</li>
 * </ul>
 *
 * <p>Additive and read-only with respect to the artifact: the source generated document /
 * credit proposal is never mutated (see {@link PrintService}); the only side effect is an
 * append-only {@code *_RENDERED} audit event carrying the X-Actor. Unknown id / reference →
 * 404; an unsupported {@code format} value → 400. Mappings are distinct from the existing
 * {@code /api/docs} and {@code /api/decisions} handlers, so nothing already wired is changed.
 */
@RestController
public class PrintController {

    private final PrintService print;

    public PrintController(PrintService print) {
        this.print = print;
    }

    /** Print / office view for a generated document (facility letter, sanction letter, …). */
    @GetMapping("/api/docs/{id}/print")
    public ResponseEntity<String> document(@PathVariable Long id,
                                           @RequestParam(value = "format", required = false) String format,
                                           @RequestHeader(value = "X-Actor", defaultValue = "system") String actor) {
        return print.renderDocument(id, format, actor);
    }

    /** Print / office view for the latest credit proposal on an application. */
    @GetMapping("/api/decisions/{reference}/credit-proposal/print")
    public ResponseEntity<String> creditProposal(@PathVariable String reference,
                                                 @RequestParam(value = "format", required = false) String format,
                                                 @RequestHeader(value = "X-Actor", defaultValue = "system") String actor) {
        return print.renderProposal(reference, format, actor);
    }
}
