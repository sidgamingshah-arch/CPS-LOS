package com.helix.decision.service;

import com.helix.common.audit.AuditService;
import com.helix.common.web.ApiException;
import com.helix.decision.client.UpstreamClient;
import com.helix.decision.client.UpstreamClient.DealEnvelopeDto;
import com.helix.decision.client.UpstreamClient.MasterRecordDto;
import com.helix.decision.client.UpstreamClient.RiskSummaryDto;
import com.helix.decision.dto.DocGenDtos.AddClauseRequest;
import com.helix.decision.entity.GeneratedDocument;
import com.helix.decision.repo.GeneratedDocumentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Document generation (PRD: template-driven proposal/facility-letter generation
 * with clause add/remove and a human-confirm gate). Pulls the {@code DOC_TEMPLATE_MASTER}
 * for the clause skeleton and {@code TNC_MASTER} for canonical clause text, weaves in
 * the live deal facts (borrower, amount, rating, hurdle/RAROC), and assembles HTML.
 *
 * <p>Every generated document starts in {@code DRAFT} and is an audited AI suggestion.
 * Confirmation records human accountability for the final wording; the document is
 * never auto-issued and is governance-only — it does <b>not</b> alter spreads,
 * rating, capital, or limits.
 */
@Service
public class DocGenService {

    private static final List<String> ALLOWED_STATUS = List.of("DRAFT", "CONFIRMED", "ISSUED", "WITHDRAWN");

    private final GeneratedDocumentRepository docs;
    private final UpstreamClient upstream;
    private final AuditService audit;

    public DocGenService(GeneratedDocumentRepository docs, UpstreamClient upstream, AuditService audit) {
        this.docs = docs;
        this.upstream = upstream;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listTemplates() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (MasterRecordDto m : upstream.masters("DOC_TEMPLATE_MASTER")) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("recordKey", m.recordKey());
            row.put("format", m.payload().getOrDefault("format", "DOCX"));
            row.put("clauses", m.payload().getOrDefault("clauses", List.of()));
            out.add(row);
        }
        return out;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listTncClauses() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (MasterRecordDto m : upstream.masters("TNC_MASTER")) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("recordKey", m.recordKey());
            row.put("text", m.payload().getOrDefault("text", ""));
            row.put("appliesTo", m.payload().getOrDefault("appliesTo", "ALL"));
            out.add(row);
        }
        return out;
    }

    // --------------------------------------------------- generate

    @Transactional
    @SuppressWarnings("unchecked")
    public GeneratedDocument generate(String reference, String templateKey, Map<String, Object> variables, String actor) {
        DealEnvelopeDto env = upstream.envelope(reference);
        RiskSummaryDto rs;
        try { rs = upstream.riskSummary(reference); } catch (Exception e) { rs = null; }
        MasterRecordDto template = findTemplate(templateKey);
        if (template == null) throw ApiException.notFound("No DOC_TEMPLATE_MASTER: " + templateKey);

        Map<String, Object> vars = new LinkedHashMap<>();
        if (variables != null) vars.putAll(variables);
        vars.putIfAbsent("borrower", env.counterpartyName());
        vars.putIfAbsent("jurisdiction", env.jurisdiction());
        vars.putIfAbsent("amount", env.totalProposedAmount());
        vars.putIfAbsent("currency", env.currency());
        vars.putIfAbsent("tenorMonths", env.tenorMonths());
        if (rs != null && rs.rating() != null) {
            vars.putIfAbsent("grade", rs.rating().finalGrade());
            vars.putIfAbsent("pd", rs.rating().pd());
        }
        if (rs != null && rs.pricing() != null) {
            vars.putIfAbsent("rate", rs.pricing().recommendedRate());
            vars.putIfAbsent("raroc", rs.pricing().raroc());
        }

        List<String> clauseKeys = (List<String>) template.payload().getOrDefault("clauses",
                List.of("definitions", "facility", "interest", "covenants", "events_of_default"));
        List<String> order = new ArrayList<>();
        Map<String, Object> clauses = new LinkedHashMap<>();
        for (String key : clauseKeys) {
            String body = renderTemplateClause(key, vars);
            order.add(key);
            clauses.put(key, clauseRow(humanise(key), body, "template:" + templateKey));
        }

        GeneratedDocument d = new GeneratedDocument();
        d.setApplicationReference(reference);
        d.setTemplateKey(templateKey);
        d.setFormat(String.valueOf(template.payload().getOrDefault("format", "DOCX")));
        d.setTitle(String.format("%s — %s", humanise(templateKey), env.counterpartyName()));
        d.setClauseOrder(order);
        d.setClauses(clauses);
        d.setVariables(vars);
        d.setStatus("DRAFT");
        d.setAdvisory(true);
        d.setGeneratedBy(actor);
        d.setHtml(renderHtml(d));
        GeneratedDocument saved = docs.save(d);

        audit.ai("doc-generator", "DOCUMENT_GENERATED", "Application", reference,
                "Generated %s from template %s (%d clauses) — DRAFT, human-confirm required"
                        .formatted(d.getTitle(), templateKey, order.size()),
                Map.of("templateKey", templateKey, "clauses", order.size(), "advisory", true));
        return saved;
    }

    // --------------------------------------------------- clause surgery

    @Transactional
    public GeneratedDocument addClause(Long docId, AddClauseRequest req, String actor) {
        GeneratedDocument d = mutable(docId);
        String clauseRef = req.clauseRef() == null ? req.tncRecordKey() : req.clauseRef();
        if (clauseRef == null || clauseRef.isBlank()) {
            throw ApiException.badRequest("clauseRef or tncRecordKey required");
        }
        if (d.getClauses().containsKey(clauseRef)) {
            throw ApiException.conflict("Clause '" + clauseRef + "' already present");
        }
        String title;
        String text;
        String source;
        if (req.tncRecordKey() != null && !req.tncRecordKey().isBlank()) {
            MasterRecordDto tnc = findTnc(req.tncRecordKey());
            if (tnc == null) throw ApiException.notFound("No TNC_MASTER: " + req.tncRecordKey());
            title = req.customTitle() != null ? req.customTitle() : humanise(req.tncRecordKey());
            text = String.valueOf(tnc.payload().getOrDefault("text", ""));
            source = "TNC_MASTER:" + req.tncRecordKey();
        } else {
            if (req.customText() == null || req.customText().isBlank()) {
                throw ApiException.badRequest("customText required when tncRecordKey is absent");
            }
            title = req.customTitle() != null ? req.customTitle() : humanise(clauseRef);
            text = req.customText();
            source = "custom:" + actor;
        }
        Map<String, Object> row = clauseRow(title, text, source);
        row.put("addedBy", actor);
        d.getClauses().put(clauseRef, row);
        List<String> order = new ArrayList<>(d.getClauseOrder());
        int pos = req.position() == null ? order.size() : Math.max(0, Math.min(order.size(), req.position()));
        order.add(pos, clauseRef);
        d.setClauseOrder(order);
        d.setHtml(renderHtml(d));
        GeneratedDocument saved = docs.save(d);
        audit.ai("doc-generator", "DOCUMENT_CLAUSE_ADDED", "GeneratedDocument", String.valueOf(docId),
                "Added clause '%s' from %s".formatted(clauseRef, source),
                Map.of("clauseRef", clauseRef, "source", source));
        return saved;
    }

    @Transactional
    public GeneratedDocument removeClause(Long docId, String clauseRef, String actor) {
        GeneratedDocument d = mutable(docId);
        if (!d.getClauses().containsKey(clauseRef)) {
            throw ApiException.notFound("No clause '" + clauseRef + "' on document");
        }
        d.getClauses().remove(clauseRef);
        List<String> order = new ArrayList<>(d.getClauseOrder());
        order.remove(clauseRef);
        d.setClauseOrder(order);
        d.setHtml(renderHtml(d));
        GeneratedDocument saved = docs.save(d);
        audit.human(actor, "DOCUMENT_CLAUSE_REMOVED", "GeneratedDocument", String.valueOf(docId),
                "Removed clause '" + clauseRef + "'", Map.of("clauseRef", clauseRef));
        return saved;
    }

    @Transactional
    @SuppressWarnings("unchecked")
    public GeneratedDocument editClause(Long docId, String clauseRef, String text, String actor) {
        GeneratedDocument d = mutable(docId);
        Map<String, Object> row = (Map<String, Object>) d.getClauses().get(clauseRef);
        if (row == null) throw ApiException.notFound("No clause '" + clauseRef + "' on document");
        row.put("text", text);
        row.put("editedBy", actor);
        row.put("source", row.getOrDefault("source", "template") + " · edited");
        d.setHtml(renderHtml(d));
        GeneratedDocument saved = docs.save(d);
        audit.human(actor, "DOCUMENT_CLAUSE_EDITED", "GeneratedDocument", String.valueOf(docId),
                "Edited clause '" + clauseRef + "'", Map.of("clauseRef", clauseRef));
        return saved;
    }

    // --------------------------------------------------- lifecycle

    @Transactional
    public GeneratedDocument confirm(Long docId, String comment, String actor) {
        GeneratedDocument d = get(docId);
        if (!"DRAFT".equals(d.getStatus())) {
            throw ApiException.conflict("Document is " + d.getStatus());
        }
        d.setStatus("CONFIRMED");
        d.setConfirmedBy(actor);
        d.setConfirmedAt(Instant.now());
        d.setConfirmComment(comment);
        GeneratedDocument saved = docs.save(d);
        audit.human(actor, "DOCUMENT_CONFIRMED", "GeneratedDocument", String.valueOf(docId),
                "Confirmed " + d.getTitle(),
                Map.of("templateKey", d.getTemplateKey(), "clauses", d.getClauseOrder().size()));
        return saved;
    }

    @Transactional
    public GeneratedDocument withdraw(Long docId, String actor) {
        GeneratedDocument d = get(docId);
        d.setStatus("WITHDRAWN");
        GeneratedDocument saved = docs.save(d);
        audit.human(actor, "DOCUMENT_WITHDRAWN", "GeneratedDocument", String.valueOf(docId),
                "Withdrew " + d.getTitle(), Map.of());
        return saved;
    }

    @Transactional(readOnly = true)
    public List<GeneratedDocument> list(String reference) {
        return docs.findByApplicationReferenceOrderByIdDesc(reference);
    }

    @Transactional(readOnly = true)
    public GeneratedDocument get(Long id) {
        return docs.findById(id).orElseThrow(() -> ApiException.notFound("No document: " + id));
    }

    // --------------------------------------------------- helpers

    private GeneratedDocument mutable(Long id) {
        GeneratedDocument d = get(id);
        if (!"DRAFT".equals(d.getStatus())) {
            throw ApiException.conflict("Document is " + d.getStatus() + " — only DRAFTs are editable");
        }
        return d;
    }

    private MasterRecordDto findTemplate(String key) {
        return upstream.masters("DOC_TEMPLATE_MASTER").stream()
                .filter(m -> m.recordKey().equalsIgnoreCase(key)).findFirst().orElse(null);
    }

    private MasterRecordDto findTnc(String key) {
        return upstream.masters("TNC_MASTER").stream()
                .filter(m -> m.recordKey().equalsIgnoreCase(key)).findFirst().orElse(null);
    }

    private Map<String, Object> clauseRow(String title, String text, String source) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("title", title);
        row.put("text", text);
        row.put("source", source);
        return row;
    }

    /** Clause boilerplate, grounded with vars — placeholder for an LLM template engine. */
    @SuppressWarnings("unchecked")
    private String renderTemplateClause(String key, Map<String, Object> vars) {
        Object amount = vars.getOrDefault("amount", 0);
        Object ccy = vars.getOrDefault("currency", "INR");
        Object tenor = vars.getOrDefault("tenorMonths", 0);
        Object borrower = vars.getOrDefault("borrower", "the Borrower");
        Object grade = vars.getOrDefault("grade", "—");
        Object rate = vars.getOrDefault("rate", null);
        return switch (key.toLowerCase()) {
            case "definitions" -> "In this Agreement, '<b>" + borrower + "</b>' means the Borrower, " +
                    "'<b>Facility</b>' means the credit facility of " + ccy + " " + fmt(amount) +
                    " described herein, and '<b>Lender</b>' means Helix Bank.";
            case "facility" -> "The Lender hereby agrees to make available to the Borrower a credit facility " +
                    "in the amount of " + ccy + " " + fmt(amount) + " for a tenor of " + tenor + " months, " +
                    "on the terms and subject to the conditions of this Agreement.";
            case "interest" -> rate == null
                    ? "Interest shall accrue at the rate determined by the Lender from time to time and shall be payable monthly in arrears."
                    : "Interest shall accrue at " + percent(rate) + " per annum and shall be payable monthly in arrears.";
            case "covenants" -> "The Borrower shall observe and perform the affirmative, negative and financial " +
                    "covenants set out in the credit decision dated of even date, including (without limitation) " +
                    "maintaining the financial covenants tested at quarterly frequency.";
            case "events_of_default" -> "An Event of Default shall occur upon non-payment of any amount when due, " +
                    "breach of any covenant uncured beyond the cure period, insolvency, cross-default, or material " +
                    "adverse change. On an Event of Default the Lender may declare the Facility immediately due and payable.";
            case "representations" -> "The Borrower represents and warrants that it is duly incorporated, has full " +
                    "power to enter into this Agreement, that the obligations herein are legal, valid, binding and " +
                    "enforceable, and that the rating assigned to it is '<b>" + grade + "</b>' on the Lender's scale.";
            case "governing_law" -> "This Agreement shall be governed by and construed in accordance with the laws of " +
                    vars.getOrDefault("jurisdiction", "India") + ", and the parties submit to the exclusive " +
                    "jurisdiction of the courts at the Lender's head office.";
            default -> "[Clause '" + key + "' — populate from template].";
        };
    }

    /** Assemble the HTML view of the document from its clauses + order. */
    @SuppressWarnings("unchecked")
    private String renderHtml(GeneratedDocument d) {
        StringBuilder sb = new StringBuilder("<article class=\"helix-doc\">")
                .append("<h1>").append(escape(d.getTitle())).append("</h1>")
                .append("<p class=\"meta\"><b>Borrower:</b> ").append(escape(String.valueOf(d.getVariables().get("borrower"))))
                .append(" &middot; <b>Amount:</b> ").append(d.getVariables().get("currency")).append(" ")
                .append(fmt(d.getVariables().get("amount")))
                .append(" &middot; <b>Tenor:</b> ").append(d.getVariables().get("tenorMonths")).append(" months")
                .append("</p>");
        int i = 1;
        for (String key : d.getClauseOrder()) {
            Map<String, Object> row = (Map<String, Object>) d.getClauses().get(key);
            if (row == null) continue;
            sb.append("<section><h2>").append(i++).append(". ")
                    .append(escape(String.valueOf(row.getOrDefault("title", key)))).append("</h2>")
                    .append("<p>").append(String.valueOf(row.getOrDefault("text", "")))
                    .append("</p><p class=\"prov\"><small>source: ")
                    .append(escape(String.valueOf(row.getOrDefault("source", "template"))))
                    .append("</small></p></section>");
        }
        sb.append("<footer><small>AI-drafted document — non-binding until human-confirmed.</small></footer>");
        sb.append("</article>");
        return sb.toString();
    }

    private String fmt(Object amount) {
        if (amount == null) return "0";
        try { return String.format("%,.0f", ((Number) amount).doubleValue()); }
        catch (Exception e) { return String.valueOf(amount); }
    }

    private String percent(Object rate) {
        try { return String.format("%.2f%%", ((Number) rate).doubleValue() * 100); }
        catch (Exception e) { return String.valueOf(rate); }
    }

    private String humanise(String key) {
        if (key == null || key.isBlank()) return "";
        String s = key.replace('_', ' ').toLowerCase();
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private String escape(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
