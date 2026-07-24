package com.helix.decision.service;

import com.helix.common.audit.AuditService;
import com.helix.common.llm.LlmClient;
import com.helix.common.llm.LlmRequest;
import com.helix.common.llm.LlmResult;
import com.helix.common.web.ApiException;
import com.helix.decision.client.UpstreamClient;
import com.helix.decision.client.UpstreamClient.DealEnvelopeDto;
import com.helix.decision.client.UpstreamClient.FacilityViewDto;
import com.helix.decision.client.UpstreamClient.MasterRecordDto;
import com.helix.decision.client.UpstreamClient.RiskSummaryDto;
import com.helix.decision.dto.DocGenDtos.AddClauseRequest;
import com.helix.decision.entity.ConditionPrecedent;
import com.helix.decision.entity.CreditDecision;
import com.helix.decision.entity.GeneratedDocument;
import com.helix.decision.repo.ConditionPrecedentRepository;
import com.helix.decision.repo.CreditDecisionRepository;
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
    private final CreditDecisionRepository decisions;
    private final ConditionPrecedentRepository conditionsPrecedent;
    private final LlmClient llm;

    public DocGenService(GeneratedDocumentRepository docs, UpstreamClient upstream, AuditService audit,
                         CreditDecisionRepository decisions, ConditionPrecedentRepository conditionsPrecedent,
                         LlmClient llm) {
        this.docs = docs;
        this.upstream = upstream;
        this.audit = audit;
        this.decisions = decisions;
        this.conditionsPrecedent = conditionsPrecedent;
        this.llm = llm;
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
        // A sanction letter weaves in the deterministic per-facility table + the decision's
        // conditions of sanction; every other template renders exactly as before (unchanged).
        if ("SANCTION_LETTER".equalsIgnoreCase(templateKey)) {
            enrichSanctionVars(reference, vars);
        }

        List<String> clauseKeys = (List<String>) template.payload().getOrDefault("clauses",
                List.of("definitions", "facility", "interest", "covenants", "events_of_default"));
        List<String> order = new ArrayList<>();
        Map<String, Object> clauses = new LinkedHashMap<>();
        for (String key : clauseKeys) {
            String body = renderTemplateClause(key, vars);
            order.add(key);
            // Template clauses are system-authored trusted HTML (see renderTemplateClause).
            clauses.put(key, clauseRow(humanise(key), body, "template:" + templateKey, true));
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

    /**
     * Generates the sanction letter for an approved deal — the artifact that follows a
     * credit decision. Requires a DECIDED decision with an APPROVE / CONDITIONAL_APPROVE
     * outcome; the letter quotes the deterministic approved figures (facilities, pricing)
     * and the conditions of sanction, is DRAFT + advisory, and is issued only after the
     * existing maker≠checker human confirm. It mutates no authoritative figure.
     */
    @Transactional
    public GeneratedDocument generateSanctionLetter(String reference, String actor) {
        CreditDecision d = decisions.findFirstByApplicationReferenceOrderByCreatedAtDesc(reference)
                .orElseThrow(() -> ApiException.conflict("No credit decision to sanction for " + reference));
        if (!"DECIDED".equals(d.getStatus())) {
            throw ApiException.conflict("Sanction letter requires a DECIDED decision (is " + d.getStatus() + ")");
        }
        if (!"APPROVE".equals(d.getOutcome()) && !"CONDITIONAL_APPROVE".equals(d.getOutcome())) {
            throw ApiException.conflict(
                    "Sanction letter only for an APPROVE / CONDITIONAL_APPROVE outcome (is " + d.getOutcome() + ")");
        }
        return generate(reference, "SANCTION_LETTER", new LinkedHashMap<>(), actor);
    }

    /**
     * Weaves the deterministic per-facility table and the decision's conditions of sanction
     * into the render vars. All figures are quoted verbatim from origination / the decision —
     * nothing is computed or mutated here.
     */
    private void enrichSanctionVars(String reference, Map<String, Object> vars) {
        DealEnvelopeDto env = upstream.envelopeOrNull(reference);
        if (env != null && env.facilities() != null) {
            List<Map<String, Object>> facs = new ArrayList<>();
            for (FacilityViewDto f : env.facilities()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("reference", f.reference());
                row.put("facilityType", f.facilityType());
                row.put("amount", f.amount());
                row.put("currency", f.currency());
                row.put("tenorMonths", f.tenorMonths());
                row.put("indicativeRate", f.indicativeRate());
                facs.add(row);
            }
            vars.put("facilities", facs);
        }
        CreditDecision d = decisions.findFirstByApplicationReferenceOrderByCreatedAtDesc(reference).orElse(null);
        List<String> conditions = new ArrayList<>();
        if (d != null && d.getConditions() != null) conditions.addAll(d.getConditions());
        for (ConditionPrecedent cp : conditionsPrecedent.findByApplicationReferenceOrderByIdAsc(reference)) {
            if ("SANCTION".equals(cp.getSource())) {
                conditions.add(cp.getCode() + ": " + cp.getTitle());
            }
        }
        vars.put("conditions", conditions);
        if (d != null) {
            vars.put("outcome", d.getOutcome());
            vars.put("requiredAuthority", d.getRequiredAuthority());
        }
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
        boolean llmDrafted = false;
        String llmModel = null;
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
            // Optional advisory LLM polish of the analyst's custom clause text. When a provider is
            // configured the model tightens the wording into formal clause prose without changing its
            // meaning; the result is still human/AI free text (HTML-escaped at render), still lands in
            // a DRAFT document, and the confirm-lock + SoD gate are unchanged. Provider 'none' (default)
            // → the custom text is used verbatim, byte-identical to today. The canonical TNC_MASTER
            // text (above) is never rewritten.
            LlmResult polished = llmPolishClause(title, text);
            if (polished.usable()) {
                text = polished.text().strip();
                llmDrafted = true;
                llmModel = polished.model();
                source = source + " · llm-polished";
            }
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
        Map<String, Object> clauseDetail = new LinkedHashMap<>();
        clauseDetail.put("clauseRef", clauseRef);
        clauseDetail.put("source", source);
        if (llmDrafted) {
            clauseDetail.put("llmDrafted", true);
            clauseDetail.put("llmModel", llmModel);
        }
        audit.ai("doc-generator", "DOCUMENT_CLAUSE_ADDED", "GeneratedDocument", String.valueOf(docId),
                "Added clause '%s' from %s".formatted(clauseRef, source),
                clauseDetail);
        return saved;
    }

    /**
     * Advisory LLM polish of an analyst's custom clause text, grounded strictly in the supplied text.
     * The model is told to preserve meaning, parties and every figure and to invent nothing; the
     * polished text remains an advisory suggestion inside a DRAFT document behind the confirm-lock +
     * SoD gate. Returns the {@link LlmResult} so the caller keeps the verbatim custom text on
     * {@code none}/failure/empty — the byte-identical default.
     */
    private LlmResult llmPolishClause(String title, String text) {
        String system = "You are polishing a single ADVISORY clause for a wholesale-credit facility/sanction "
                + "document (capability=docgen-clause). Rewrite the supplied clause text into clear, formal legal "
                + "prose WITHOUT changing its meaning. Preserve every party name, amount, rate, date and defined "
                + "term exactly as given and never invent, add or remove an obligation or a figure. This is an "
                + "advisory suggestion inside a DRAFT document that a named human must confirm. Reply with only the "
                + "revised clause text.";
        String user = "Clause title: " + (title == null ? "" : title) + "\nClause text to polish:\n" + text;
        return safeComplete(LlmRequest.of("docgen-clause", system, user));
    }

    private LlmResult safeComplete(LlmRequest req) {
        try {
            LlmResult r = llm.complete(req);
            return r == null ? LlmResult.notConfigured() : r;
        } catch (Exception e) {
            return LlmResult.failed(e.getMessage());
        }
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
        // Edited text is human free text — never trust it as raw HTML (escape at render).
        row.put("trustedHtml", false);
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
        if (actor.equalsIgnoreCase(d.getGeneratedBy())) {
            throw ApiException.forbiddenAutonomy(
                    "Confirmer '" + actor + "' cannot confirm a document they generated — maker must differ from checker");
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
        return clauseRow(title, text, source, false);
    }

    /**
     * Builds a clause row. {@code trustedHtml=true} marks a system-authored template
     * clause whose body is intentional HTML (bold, per-facility tables, ordered lists)
     * and is emitted verbatim. Human/AI-authored free text (TNC clauses, custom text,
     * edited clauses) leaves the flag off so {@link #renderHtml} HTML-escapes it — a
     * {@code <script>} in clause text is rendered inert.
     */
    private Map<String, Object> clauseRow(String title, String text, String source, boolean trustedHtml) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("title", title);
        row.put("text", text);
        row.put("source", source);
        row.put("trustedHtml", trustedHtml);
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
        Object raroc = vars.getOrDefault("raroc", null);
        Object jur = vars.getOrDefault("jurisdiction", "India");
        return switch (key.toLowerCase()) {
            case "sanction_summary" -> "Helix Bank is pleased to advise the sanction of credit facilities to " +
                    "<b>" + borrower + "</b> aggregating " + ccy + " " + fmt(amount) + ", on the terms set out below " +
                    "and subject to the conditions of sanction. Rating assigned: <b>" + grade + "</b>. " +
                    "This letter, once accepted, together with the executed facility documentation, constitutes the " +
                    "agreement between the Borrower and the Lender in respect of the facilities and supersedes all " +
                    "prior term sheets and indicative offers.";
            case "approved_facilities" -> renderFacilitiesTable(vars, ccy, amount, tenor);
            case "pricing_terms" -> (rate == null
                    ? "Pricing shall be as advised by the Lender in accordance with its schedule of charges."
                    : "Indicative all-in rate: <b>" + percent(rate) + " per annum</b>" +
                    (raroc == null ? "" : " (risk-adjusted return " + percent(raroc) + ")") +
                    ", reset in line with the applicable benchmark.") +
                    " Interest is computed on the daily outstanding on an actual/365 basis and is payable monthly in " +
                    "arrears. Rate resets take effect on each benchmark reset date; the Lender's schedule of charges " +
                    "governs processing, commitment, documentation and other fees.";
            case "conditions_precedent" -> renderConditions(vars);
            case "conditions_general" -> "The Borrower shall comply with the Lender's general covenants and " +
                    "reporting obligations, maintain the agreed security, and furnish periodic financials and " +
                    "compliance certificates throughout the currency of the facilities. The Borrower shall utilise " +
                    "the facilities solely for the sanctioned purpose, permit inspection of the charged assets and " +
                    "books of account, and promptly notify the Lender of any event that could constitute an event of " +
                    "default or a material adverse change.";
            case "validity" -> "This sanction is valid for 90 days from the date of this letter, within which the " +
                    "facility documentation must be executed and the conditions precedent satisfied, failing which " +
                    "the sanction shall lapse unless extended in writing by the Lender. The Lender reserves the right " +
                    "to review, modify or withdraw the sanction on the occurrence of any material adverse change in " +
                    "the Borrower's financial position or the security prior to first drawdown.";
            case "acceptance" -> "Kindly signify acceptance of this sanction by returning a countersigned copy of " +
                    "this letter together with the board resolution authorising acceptance of the facilities. " +
                    "Acceptance shall be unconditional; any qualification or counter-proposal shall be treated as a " +
                    "fresh request and shall not bind the Lender until re-sanctioned.";
            case "security_summary" -> "The facilities shall be secured by the security described in the facility " +
                    "documentation, including the charge(s) over the assets offered as collateral, duly registered " +
                    "with the applicable registries (ROC / CERSAI, or the relevant registry in " + jur + "), together " +
                    "with the guarantee(s), if any, of the named guarantors. All security shall rank in favour of the " +
                    "Lender and shall be perfected prior to first drawdown.";
            case "covenants_summary" -> "The facilities are subject to the affirmative, negative, information and " +
                    "financial covenants set out in the credit decision of even date, including the financial " +
                    "covenants tested at the agreed frequency. Breach of a covenant, uncured within the applicable " +
                    "cure period, entitles the Lender to exercise its rights under the events-of-default provisions.";
            case "insurance_summary" -> "The Borrower shall keep the charged assets comprehensively insured for their " +
                    "full reinstatement value against all usual risks, with the Lender named as loss payee / mortgagee, " +
                    "and shall assign the policies to the Lender and produce evidence of renewal on each due date.";
            case "fees_summary" -> "Processing, commitment, documentation and other fees are payable per the Lender's " +
                    "schedule of charges as intimated separately, together with all applicable stamp duty, " +
                    "registration charges and taxes, which shall be borne by the Borrower.";
            case "definitions" -> "In this Agreement, '<b>" + borrower + "</b>' means the Borrower, " +
                    "'<b>Facility</b>' means the credit facility of " + ccy + " " + fmt(amount) +
                    " described herein, and '<b>Lender</b>' means Helix Bank. '<b>Business Day</b>' means a day on " +
                    "which banks are open for general business in the jurisdiction of the Lender's head office; " +
                    "'<b>Security</b>' means the security created or to be created under the Security Documents; and " +
                    "'<b>Event of Default</b>' has the meaning given in the events-of-default clause. References to a " +
                    "party include its permitted successors and assigns.";
            case "facility" -> "The Lender hereby agrees to make available to the Borrower a credit facility " +
                    "in the amount of " + ccy + " " + fmt(amount) + " for a tenor of " + tenor + " months, " +
                    "on the terms and subject to the conditions of this Agreement. The Facility shall be availed in " +
                    "one or more drawdowns during the availability period and shall be repaid in accordance with the " +
                    "agreed repayment schedule; amounts repaid on a term facility may not be redrawn unless expressly " +
                    "agreed in writing.";
            case "drawdown" -> "Each drawdown shall be made by an irrevocable drawdown request delivered to the Lender " +
                    "not less than two Business Days before the proposed drawdown date, specifying the amount and the " +
                    "value date. A drawdown is subject to the satisfaction (or waiver in writing) of all conditions " +
                    "precedent, the accuracy of the representations and warranties on the drawdown date, and no Event " +
                    "of Default having occurred and being continuing. The proceeds shall be applied solely towards the " +
                    "sanctioned purpose.";
            case "interest" -> (rate == null
                    ? "Interest shall accrue at the rate determined by the Lender from time to time and shall be payable monthly in arrears."
                    : "Interest shall accrue at " + percent(rate) + " per annum and shall be payable monthly in arrears.") +
                    " Interest is calculated on the daily outstanding balance on an actual/365-day basis. Where the " +
                    "rate is linked to a benchmark, it shall be reset on each reset date by reference to the then-" +
                    "prevailing benchmark plus the agreed spread.";
            case "default_interest" -> "Without prejudice to the Lender's other rights, default interest at 2.00% per " +
                    "annum above the applicable rate shall accrue on any amount not paid when due, from the due date " +
                    "until actual payment, and shall be compounded at monthly rests. The levy of default interest " +
                    "shall not be construed as a waiver of any Event of Default arising from the non-payment.";
            case "security_and_perfection" -> "As continuing security for the facilities the Borrower shall create and " +
                    "perfect, prior to first drawdown, a first-ranking charge over the assets described in the Security " +
                    "Documents, duly registered with the Registrar of Companies (Form CHG-1) and CERSAI where " +
                    "applicable, or with the relevant registry in " + jur + ". The Borrower shall not create or permit " +
                    "any further charge, lien or encumbrance over the secured assets without the Lender's prior " +
                    "written consent.";
            case "financial_covenants" -> "The Borrower shall maintain, and test at the agreed frequency, the financial " +
                    "covenants specified in the credit decision of even date (including, without limitation, the debt-" +
                    "service coverage, leverage and current-ratio covenants). Compliance shall be certified in each " +
                    "compliance certificate; the Lender may recompute any covenant from the Borrower's audited or " +
                    "management financials, and its computation shall prevail in the event of a discrepancy.";
            case "information_covenants" -> "The Borrower shall furnish to the Lender: audited financial statements " +
                    "within 180 days of each financial year-end; management accounts and a covenant compliance " +
                    "certificate at the agreed frequency; the annual business plan; and such other information as the " +
                    "Lender may reasonably require. The Borrower shall promptly notify the Lender of any litigation, " +
                    "regulatory action or event that is or may become material to its ability to perform its " +
                    "obligations.";
            case "covenants" -> "The Borrower shall observe and perform the affirmative, negative and financial " +
                    "covenants set out in the credit decision dated of even date, including (without limitation) " +
                    "maintaining the financial covenants tested at quarterly frequency. The Borrower shall preserve " +
                    "its corporate existence and material authorisations, comply with all applicable laws, and refrain " +
                    "from any change of control, merger, material disposal or dividend that breaches the negative " +
                    "covenants without the Lender's prior written consent.";
            case "prepayment" -> "The Borrower may prepay the Facility in whole or in part on giving not less than " +
                    "seven Business Days' prior written notice, together with accrued interest and any applicable " +
                    "prepayment premium and break costs. Amounts prepaid on a term facility may not be redrawn. The " +
                    "Lender may require mandatory prepayment on the occurrence of specified events, including a change " +
                    "of control or a material asset disposal.";
            case "fees_and_charges" -> "The Borrower shall pay the processing, commitment, documentation, agency and " +
                    "other fees set out in the Lender's schedule of charges, together with all stamp duty, " +
                    "registration and other charges and all applicable taxes. All payments shall be made free and " +
                    "clear of, and without deduction for, any taxes save as required by law, in which case the " +
                    "Borrower shall gross up the payment.";
            case "events_of_default" -> "An Event of Default shall occur upon non-payment of any amount when due, " +
                    "breach of any covenant uncured beyond the cure period, insolvency, cross-default, or material " +
                    "adverse change. On an Event of Default the Lender may declare the Facility immediately due and payable. " +
                    "Further Events of Default include a misrepresentation, the invalidity or unenforceability of any " +
                    "Security Document, the levy of distress or attachment on the secured assets, and the cessation or " +
                    "material change of the Borrower's business.";
            case "cross_default" -> "An Event of Default shall be deemed to occur if any financial indebtedness of the " +
                    "Borrower or any guarantor is not paid when due (after any applicable grace period) or becomes, or " +
                    "becomes capable of being declared, due and payable prior to its stated maturity by reason of an " +
                    "event of default (howsoever described), or any commitment for such indebtedness is cancelled or " +
                    "suspended by a creditor by reason of a default.";
            case "representations" -> "The Borrower represents and warrants that it is duly incorporated, has full " +
                    "power to enter into this Agreement, that the obligations herein are legal, valid, binding and " +
                    "enforceable, and that the rating assigned to it is '<b>" + grade + "</b>' on the Lender's scale. " +
                    "The Borrower further represents that its most recent financial statements fairly present its " +
                    "financial position, that no Event of Default is continuing, and that no litigation is pending or " +
                    "threatened that could have a material adverse effect.";
            case "indemnity" -> "The Borrower shall indemnify the Lender on demand against any loss, cost, claim, " +
                    "liability or expense (including legal fees) incurred by the Lender as a consequence of the " +
                    "occurrence of any Event of Default, any drawdown not made by reason of the Borrower's default, " +
                    "any funding break cost, or the enforcement or preservation of the Lender's rights under the " +
                    "facility documentation.";
            case "force_majeure" -> "Neither party shall be liable for any failure or delay in performing its " +
                    "obligations (other than a payment obligation) to the extent caused by an event beyond its " +
                    "reasonable control, provided that it notifies the other party and uses reasonable endeavours to " +
                    "mitigate the effect. A payment obligation is never excused by force majeure.";
            case "confidentiality" -> "Each party shall keep confidential all non-public information received from the " +
                    "other in connection with the facilities and shall not disclose it save to its officers, advisers, " +
                    "regulators and permitted assignees on a need-to-know basis, or as required by law or by a court " +
                    "or regulator of competent jurisdiction.";
            case "set_off" -> "The Lender may, without prior notice, combine or consolidate all or any of the " +
                    "Borrower's accounts and set off any matured obligation owed by the Borrower against any obligation " +
                    "of the Lender to the Borrower, applying for that purpose any applicable rate of exchange. The " +
                    "Lender shall notify the Borrower promptly after any exercise of this right.";
            case "assignment" -> "The Borrower shall not assign or transfer any of its rights or obligations under the " +
                    "facility documentation without the Lender's prior written consent. The Lender may assign, transfer " +
                    "or novate any of its rights and obligations, or grant a participation, to any bank, financial " +
                    "institution or other person, and may disclose to a prospective assignee such information as is " +
                    "reasonably required.";
            case "governing_law_jurisdiction" -> "This Agreement and any non-contractual obligations arising out of or " +
                    "in connection with it are governed by the laws of " + jur + ". The parties submit to the exclusive " +
                    "jurisdiction of the courts at the Lender's head office, save that the Lender may bring proceedings " +
                    "in any other court of competent jurisdiction to enforce its security.";
            case "governing_law" -> "This Agreement shall be governed by and construed in accordance with the laws of " +
                    jur + ", and the parties submit to the exclusive " +
                    "jurisdiction of the courts at the Lender's head office.";
            default -> "[Clause '" + key + "' — populate from template].";
        };
    }

    /** Deterministic per-facility sanction table from the woven {@code facilities} var. */
    @SuppressWarnings("unchecked")
    private String renderFacilitiesTable(Map<String, Object> vars, Object ccy, Object amount, Object tenor) {
        Object raw = vars.get("facilities");
        if (!(raw instanceof List<?> list) || list.isEmpty()) {
            return "Facility: " + ccy + " " + fmt(amount) + " for a tenor of " + tenor + " months.";
        }
        StringBuilder sb = new StringBuilder("<table class=\"facilities\"><thead><tr>")
                .append("<th>Facility</th><th>Type</th><th>Amount</th><th>Tenor (months)</th><th>Indicative rate</th>")
                .append("</tr></thead><tbody>");
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> raw2)) continue;
            Map<String, Object> f = (Map<String, Object>) raw2;
            Object rate = f.get("indicativeRate");
            sb.append("<tr><td>").append(escape(String.valueOf(f.get("reference"))))
                    .append("</td><td>").append(escape(String.valueOf(f.get("facilityType"))))
                    .append("</td><td>").append(f.getOrDefault("currency", ccy)).append(" ").append(fmt(f.get("amount")))
                    .append("</td><td>").append(f.getOrDefault("tenorMonths", tenor))
                    .append("</td><td>").append(rate == null ? "—" : percent(rate))
                    .append("</td></tr>");
        }
        sb.append("</tbody></table>");
        return sb.toString();
    }

    /** The conditions-of-sanction list from the woven {@code conditions} var. */
    private String renderConditions(Map<String, Object> vars) {
        Object raw = vars.get("conditions");
        if (!(raw instanceof List<?> list) || list.isEmpty()) {
            return "The facilities are sanctioned subject to the Lender's standard conditions precedent to drawdown.";
        }
        StringBuilder sb = new StringBuilder("The following conditions must be satisfied prior to drawdown:<ol>");
        for (Object c : list) {
            sb.append("<li>").append(escape(String.valueOf(c))).append("</li>");
        }
        sb.append("</ol>");
        return sb.toString();
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
            // System-authored template clauses carry intentional HTML (bold, tables) and are
            // emitted verbatim; human/AI-authored free text is HTML-escaped so injected markup
            // (e.g. a <script>) is rendered inert. Defense-in-depth at the source of the body.
            String text = String.valueOf(row.getOrDefault("text", ""));
            String body = Boolean.TRUE.equals(row.get("trustedHtml")) ? text : mdToHtml(text);
            sb.append("<section><h2>").append(i++).append(". ")
                    .append(escape(String.valueOf(row.getOrDefault("title", key)))).append("</h2>")
                    .append("<p>").append(body)
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

    /**
     * Escape human/AI-authored free text, then re-enable a tiny, safe Markdown subset
     * ({@code **bold**} / {@code _italic_}). Mirrors CreditProposalService's {@code mdToHtml}
     * so a {@code <script>} in the text becomes {@code &lt;script&gt;} (inert) while ordinary
     * emphasis still renders.
     */
    private String mdToHtml(String s) {
        return escape(s).replaceAll("\\*\\*(.+?)\\*\\*", "<b>$1</b>").replaceAll("_(.+?)_", "<i>$1</i>");
    }
}
