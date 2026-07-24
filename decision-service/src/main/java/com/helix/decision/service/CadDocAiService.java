package com.helix.decision.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.helix.common.audit.AuditService;
import com.helix.common.governance.AiCapability;
import com.helix.common.governance.AiGovernanceClient;
import com.helix.common.llm.LlmClient;
import com.helix.common.llm.LlmRequest;
import com.helix.common.llm.LlmResult;
import com.helix.common.web.ApiException;
import com.helix.decision.client.UpstreamClient;
import com.helix.decision.dto.CadDtos.VerifyDocRequest;
import com.helix.decision.entity.CadCase;
import com.helix.decision.entity.CadDocVerification;
import com.helix.decision.entity.ChecklistItem;
import com.helix.decision.repo.CadCaseRepository;
import com.helix.decision.repo.CadDocVerificationRepository;
import com.helix.decision.repo.ChecklistItemRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * CAD document-AI (PRD CAD documentation-perfection): an ADVISORY overlay on a checklist
 * item that verifies its supporting document. Two flavours:
 *
 * <ul>
 *   <li><b>SIGNATURE</b> — compares a claimed signatory against the on-file specimen name
 *       with a deterministic normalised token heuristic (optionally sharpened by a governed
 *       {@link LlmClient} second opinion) and produces a MATCH / MISMATCH / INCONCLUSIVE
 *       verdict.</li>
 *   <li><b>PROPERTY_DOC / TITLE</b> — extracts the key title/property-document fields from
 *       the document text with the platform's regex-extraction approach (optionally topped up
 *       by an LLM overlay that only ever FILLS a field the regex missed), flags missing
 *       mandatory fields, and produces a COMPLETE / INCOMPLETE / INCONCLUSIVE verdict.</li>
 * </ul>
 *
 * <p><b>Governance.</b> {@code governance.enforce(CAD_DOC_AI, jurisdiction)} gates the run;
 * the finding is a separate {@link CadDocVerification} entity ({@code advisory=true}, DRAFT)
 * stamped {@code audit.ai("cad-doc-ai", ...)}; a named human {@code accept}/{@code reject}s it,
 * stamping {@code audit.human(...)}. The AI <b>never</b> moves the {@link ChecklistItem} to
 * COMPLIED — the human still sets the item status through the existing CAD workflow. It is
 * fail-soft: no LLM configured (the default) → deterministic finding, never a crash.</p>
 */
@Service
public class CadDocAiService {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String CAP = "cad-doc-ai";

    /** Default mandatory title/property-document fields when the caller doesn't override. */
    private static final List<String> DEFAULT_PROPERTY_FIELDS =
            List.of("OWNER", "PROPERTY_ADDRESS", "SURVEY_OR_PLOT_NO", "DEED_OR_REGISTRATION_NO", "AREA");

    /** Honorifics stripped before a signatory-name comparison so "Mr A. Rao" ~ "A Rao". */
    private static final Set<String> HONORIFICS =
            Set.of("mr", "mrs", "ms", "dr", "shri", "smt", "sri", "m/s", "messrs");

    private final CadCaseRepository cases;
    private final ChecklistItemRepository items;
    private final CadDocVerificationRepository verifications;
    private final UpstreamClient upstream;
    private final AuditService audit;
    private final AiGovernanceClient governance;
    private final LlmClient llm;

    public CadDocAiService(CadCaseRepository cases, ChecklistItemRepository items,
                           CadDocVerificationRepository verifications, UpstreamClient upstream,
                           AuditService audit, AiGovernanceClient governance, LlmClient llm) {
        this.cases = cases;
        this.items = items;
        this.verifications = verifications;
        this.upstream = upstream;
        this.audit = audit;
        this.governance = governance;
        this.llm = llm;
    }

    // ====================================================== verify

    @Transactional
    public CadDocVerification verifyDoc(Long caseId, Long itemId, VerifyDocRequest req, String actor) {
        CadCase cadCase = cases.findById(caseId)
                .orElseThrow(() -> ApiException.notFound("No CAD case: " + caseId));
        ChecklistItem item = items.findById(itemId)
                .orElseThrow(() -> ApiException.notFound("No item: " + itemId));
        if (!caseId.equals(item.getCadCaseId())) {
            throw ApiException.badRequest("Checklist item " + itemId + " does not belong to CAD case " + caseId);
        }

        // Gate on the deal's jurisdiction (best-effort; null when origination is unreachable).
        String jurisdiction = null;
        try {
            UpstreamClient.DealEnvelopeDto env = upstream.envelopeOrNull(cadCase.getApplicationRef());
            jurisdiction = env == null ? null : env.jurisdiction();
        } catch (Exception ignore) {
            // fail-soft — enforce with a null jurisdiction (default AI_GOVERNANCE record applies)
        }
        governance.enforce(AiCapability.CAD_DOC_AI, jurisdiction);

        String type = req != null && req.verificationType() != null
                ? req.verificationType().trim().toUpperCase() : "PROPERTY_DOC";
        boolean signature = type.startsWith("SIGN");

        CadDocVerification v = new CadDocVerification();
        v.setCadCaseId(caseId);
        v.setChecklistItemId(itemId);
        v.setApplicationRef(cadCase.getApplicationRef());
        v.setItemDescription(clamp(item.getDescription(), 400));
        v.setVerificationType(signature ? "SIGNATURE" : "PROPERTY_DOC");
        v.setAdvisory(true);
        v.setStatus("DRAFT");
        v.setCreatedBy(actor);

        if (signature) {
            verifySignature(v, req);
        } else {
            verifyPropertyDoc(v, req);
        }

        CadDocVerification saved = verifications.save(v);

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("verificationType", saved.getVerificationType());
        detail.put("verdict", saved.getVerdict());
        detail.put("confidence", saved.getConfidence());
        detail.put("advisory", true);
        detail.put("checklistItemId", itemId);
        detail.put("checklistItemStatus", item.getStatus());   // proves the AI did not move it
        if (saved.getMissingFields() != null && !saved.getMissingFields().isEmpty()) {
            detail.put("missingFields", saved.getMissingFields());
        }
        if (saved.isLlmDrafted()) {
            detail.put("llmDrafted", true);
            detail.put("llmModel", saved.getLlmModel());
        }
        audit.ai(CAP, "CAD_DOC_VERIFIED", "CadItem", String.valueOf(itemId),
                "%s check on '%s' -> %s (%.2f) — advisory, item status unchanged (%s)".formatted(
                        saved.getVerificationType(), item.getDescription(), saved.getVerdict(),
                        saved.getConfidence(), item.getStatus()),
                detail);
        return saved;
    }

    // ---- signature ----

    private void verifySignature(CadDocVerification v, VerifyDocRequest req) {
        String claimed = req == null ? null : req.claimedSignatory();
        String specimen = req == null ? null : req.specimenSignatory();
        v.setClaimedSignatory(clamp(claimed, 200));
        v.setSpecimenSignatory(clamp(specimen, 200));
        if (req != null && req.docText() != null) {
            v.setSourceExcerpt(clamp(req.docText(), 1000));
        }
        List<String> findings = new ArrayList<>();

        if (isBlank(claimed) || isBlank(specimen)) {
            findings.add("Cannot compare — " + (isBlank(claimed) ? "claimed signatory" : "specimen signatory")
                    + " not supplied. Human review required.");
            v.setVerdict("INCONCLUSIVE");
            v.setConfidence(0.2);
            v.setFindings(findings);
            v.setSummary("Signature check inconclusive — missing input.");
            llmSignatureOpinion(v, claimed, specimen, findings);
            return;
        }

        String nc = normaliseName(claimed);
        String ns = normaliseName(specimen);
        findings.add("Claimed signatory normalised: '" + nc + "'");
        findings.add("Specimen on file normalised: '" + ns + "'");

        double sim = tokenSimilarity(nc, ns);
        findings.add("Token-set similarity: " + String.format("%.2f", sim));

        String verdict;
        double confidence;
        if (nc.equals(ns)) {
            verdict = "MATCH";
            confidence = 0.95;
            findings.add("Exact normalised-name match against the on-file specimen.");
        } else if (sim >= 0.6) {
            verdict = "MATCH";
            confidence = Math.min(0.9, 0.55 + 0.35 * sim);
            findings.add("Names agree on the significant tokens — advisory MATCH (human to countersign).");
        } else if (sim >= 0.3) {
            verdict = "INCONCLUSIVE";
            confidence = 0.45;
            findings.add("Partial overlap only — a spelling variant or a different signatory. Human review required.");
        } else {
            verdict = "MISMATCH";
            confidence = Math.min(0.9, 0.6 + 0.3 * (1 - sim));
            findings.add("Claimed signatory does not match the on-file specimen — advisory MISMATCH; do NOT comply without review.");
        }
        v.setVerdict(verdict);
        v.setConfidence(round2(confidence));
        v.setSummary("Signature '" + clamp(claimed, 60) + "' vs specimen '" + clamp(specimen, 60) + "' -> " + verdict);
        // Optional advisory LLM second opinion (fail-soft; verdict/confidence unchanged).
        llmSignatureOpinion(v, claimed, specimen, findings);
        v.setFindings(clampList(findings));
    }

    // ---- property / title document ----

    private void verifyPropertyDoc(CadDocVerification v, VerifyDocRequest req) {
        String text = req == null ? null : req.docText();
        v.setSourceExcerpt(text == null ? null : clamp(text, 1000));
        List<String> mandatory = req != null && req.mandatoryFields() != null && !req.mandatoryFields().isEmpty()
                ? normaliseFieldKeys(req.mandatoryFields()) : DEFAULT_PROPERTY_FIELDS;

        List<String> findings = new ArrayList<>();
        Map<String, Object> extracted = new LinkedHashMap<>();

        if (isBlank(text)) {
            findings.add("No document text supplied — cannot extract title/property fields. Human review required.");
            v.setVerdict("INCONCLUSIVE");
            v.setConfidence(0.2);
            v.setFindings(findings);
            v.setMissingFields(new ArrayList<>(mandatory));
            v.setExtractedFields(extracted);
            v.setSummary("Property-document check inconclusive — no text.");
            return;
        }

        // Deterministic regex extraction.
        for (String field : mandatory) {
            String value = extractField(field, text);
            if (value != null && !value.isBlank()) {
                extracted.put(field, clamp(value.trim(), 200));
                findings.add("Extracted " + humanField(field) + ": " + clamp(value.trim(), 120));
            }
        }
        // Informational: charge / encumbrance / mortgage mentions.
        if (matches(text, "\\b(mortgage|charge|hypothecation|lien|encumbrance)\\b")) {
            findings.add("Note: the document references an existing charge/encumbrance — confirm prior-charge status.");
            extracted.put("CHARGE_MENTIONED", true);
        }

        // Optional advisory LLM overlay — only FILLS fields the regex missed (fail-soft).
        llmPropertyOverlay(v, text, mandatory, extracted, findings);

        // Re-derive missing fields + verdict deterministically after any overlay.
        List<String> missing = new ArrayList<>();
        for (String field : mandatory) {
            if (!extracted.containsKey(field)) {
                missing.add(field);
                findings.add("MISSING mandatory field: " + humanField(field));
            }
        }
        int found = mandatory.size() - missing.size();
        double confidence = round2(0.4 + 0.5 * (mandatory.isEmpty() ? 1.0 : (double) found / mandatory.size()));
        String verdict = missing.isEmpty() ? "COMPLETE" : "INCOMPLETE";
        findings.add(0, "Extracted " + found + "/" + mandatory.size() + " mandatory field(s) -> advisory " + verdict
                + " (human to comply the item).");

        v.setExtractedFields(extracted);
        v.setMissingFields(missing);
        v.setVerdict(verdict);
        v.setConfidence(confidence);
        v.setSummary("Title/property document: " + found + "/" + mandatory.size() + " mandatory field(s) present -> " + verdict);
        v.setFindings(clampList(findings));
    }

    // ====================================================== human gate

    @Transactional
    public CadDocVerification decide(Long verificationId, boolean accept, String note, String actor) {
        CadDocVerification v = verifications.findById(verificationId)
                .orElseThrow(() -> ApiException.notFound("No doc verification: " + verificationId));
        if (!"DRAFT".equals(v.getStatus())) {
            throw ApiException.conflict("Doc verification already " + v.getStatus());
        }
        v.setStatus(accept ? "ACCEPTED" : "REJECTED");
        v.setReviewedBy(actor);
        v.setReviewedAt(Instant.now());
        v.setReviewNote(note);
        CadDocVerification saved = verifications.save(v);
        audit.human(actor, accept ? "CAD_DOC_VERIFICATION_ACCEPTED" : "CAD_DOC_VERIFICATION_REJECTED",
                "CadItem", String.valueOf(v.getChecklistItemId()),
                "%s advisory %s finding (%s) on checklist item %d".formatted(
                        accept ? "Accepted" : "Rejected", v.getVerificationType(), v.getVerdict(),
                        v.getChecklistItemId()),
                Map.of("verificationId", verificationId, "verdict", String.valueOf(v.getVerdict()),
                        "accepted", accept));
        return saved;
    }

    @Transactional(readOnly = true)
    public List<CadDocVerification> listForCase(Long caseId) {
        return verifications.findByCadCaseIdOrderByIdDesc(caseId);
    }

    @Transactional(readOnly = true)
    public CadDocVerification get(Long id) {
        return verifications.findById(id).orElseThrow(() -> ApiException.notFound("No doc verification: " + id));
    }

    // ====================================================== deterministic helpers

    private static String normaliseName(String s) {
        if (s == null) return "";
        String[] toks = s.toLowerCase().replaceAll("[^a-z0-9\\s]", " ").trim().split("\\s+");
        List<String> keep = new ArrayList<>();
        for (String t : toks) {
            if (t.isBlank() || HONORIFICS.contains(t)) continue;
            keep.add(t);
        }
        return String.join(" ", keep);
    }

    /** Jaccard similarity over the significant name tokens. */
    private static double tokenSimilarity(String a, String b) {
        Set<String> sa = new LinkedHashSet<>(List.of(a.isBlank() ? new String[0] : a.split("\\s+")));
        Set<String> sb = new LinkedHashSet<>(List.of(b.isBlank() ? new String[0] : b.split("\\s+")));
        if (sa.isEmpty() && sb.isEmpty()) return 1.0;
        if (sa.isEmpty() || sb.isEmpty()) return 0.0;
        Set<String> inter = new LinkedHashSet<>(sa);
        inter.retainAll(sb);
        Set<String> union = new LinkedHashSet<>(sa);
        union.addAll(sb);
        return (double) inter.size() / union.size();
    }

    /** Regex extraction of one title/property-document field. Returns null when absent. */
    private static String extractField(String field, String text) {
        String pattern = switch (field) {
            case "OWNER" -> "(?:owner|owned by|in the name of|vendor|mortgagor|title holder)\\s*[:\\-]?\\s*([^\\n\\r.;]{2,120})";
            case "PROPERTY_ADDRESS" -> "(?:address|situated at|property at|premises|bearing address)\\s*[:\\-]?\\s*([^\\n\\r;]{4,160})";
            case "SURVEY_OR_PLOT_NO" -> "(?:survey no|survey number|plot no|plot number|s\\.?no|khasra|gat no)\\s*[:\\-]?\\s*([A-Za-z0-9/\\-]{1,40})";
            case "DEED_OR_REGISTRATION_NO" -> "(?:deed no|registration no|document no|reg\\.?\\s*no|registered as|instrument no)\\s*[:\\-]?\\s*([A-Za-z0-9/\\-]{1,40})";
            case "AREA" -> "(?:area|admeasuring|extent|built[- ]?up)\\s*[:\\-]?\\s*([0-9][0-9,\\.]*\\s*(?:sq\\.?\\s*(?:ft|feet|m|mtr|metres|meters|yards)|acres|hectares)?)";
            default -> null;
        };
        if (pattern == null) return null;
        Matcher m = Pattern.compile(pattern, Pattern.CASE_INSENSITIVE).matcher(text);
        if (m.find()) {
            String g = m.group(1);
            return g == null ? null : g.trim();
        }
        return null;
    }

    private static boolean matches(String text, String regex) {
        return Pattern.compile(regex, Pattern.CASE_INSENSITIVE).matcher(text).find();
    }

    private static List<String> normaliseFieldKeys(List<String> raw) {
        List<String> out = new ArrayList<>();
        for (String s : raw) {
            if (s == null || s.isBlank()) continue;
            out.add(s.trim().toUpperCase().replace(' ', '_'));
        }
        return out.isEmpty() ? DEFAULT_PROPERTY_FIELDS : out;
    }

    private static String humanField(String field) {
        return field == null ? "" : field.replace('_', ' ').toLowerCase();
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static double round2(double d) {
        return Math.round(d * 100.0) / 100.0;
    }

    private static String clamp(String s, int max) {
        if (s == null) return null;
        return s.length() > max ? s.substring(0, max) : s;
    }

    /** Keep the findings list within the persisted column budget. */
    private static List<String> clampList(List<String> findings) {
        List<String> out = new ArrayList<>();
        int budget = 1900;
        for (String f : findings) {
            String c = clamp(f, 240);
            if (budget - c.length() < 0) break;
            budget -= c.length();
            out.add(c);
        }
        return out;
    }

    // ====================================================== advisory LLM overlays (fail-soft)

    /**
     * Advisory LLM second opinion on the signature comparison. Appends the model's rationale as an
     * extra finding and records the model; the deterministic verdict/confidence are UNCHANGED. Silent
     * no-op when the provider is 'none' / errors / empty (the byte-identical default).
     */
    private void llmSignatureOpinion(CadDocVerification v, String claimed, String specimen, List<String> findings) {
        String system = "You are an ADVISORY signature-verification assistant for wholesale-credit document "
                + "administration (capability=cad-doc-ai). Compare a CLAIMED signatory name against the on-file "
                + "SPECIMEN name and give a one-sentence rationale for whether they plausibly denote the same person. "
                + "You do NOT decide document compliance and you never invent a name. Reply with only the sentence.";
        String user = "Claimed signatory: " + claimed + "\nSpecimen on file: " + specimen;
        LlmResult r = safeComplete(LlmRequest.of(CAP, system, user));
        if (r.usable()) {
            findings.add("AI second opinion: " + clamp(r.text().strip(), 200));
            v.setLlmDrafted(true);
            v.setLlmModel(r.model());
        }
    }

    /**
     * Advisory LLM overlay that only FILLS title/property-document fields the regex missed. It never
     * overrides a regex-extracted field and the missing-list + verdict are re-derived deterministically
     * afterwards. Silent no-op on provider 'none' / error / unparseable (the byte-identical default).
     */
    private void llmPropertyOverlay(CadDocVerification v, String text, List<String> mandatory,
                                    Map<String, Object> extracted, List<String> findings) {
        List<String> stillMissing = new ArrayList<>();
        for (String f : mandatory) {
            if (!extracted.containsKey(f)) stillMissing.add(f);
        }
        if (stillMissing.isEmpty()) {
            return;   // nothing for the model to add
        }
        String system = "You are an ADVISORY title/property-document reading assistant for wholesale-credit "
                + "documentation (capability=cad-doc-ai). Extract ONLY the requested fields that are explicitly "
                + "present in the document text and return ONLY a JSON object mapping the field key to the extracted "
                + "value (omit a field entirely if it is not present). Never invent a value. Field keys: "
                + String.join(", ", stillMissing) + ".";
        String user = "Document text:\n" + text;
        LlmResult r = safeComplete(LlmRequest.of(CAP, system, user));
        if (!r.usable()) {
            return;
        }
        Map<String, Object> obj = parseJsonObject(r.text());
        if (obj == null) {
            return;
        }
        boolean filled = false;
        for (String field : stillMissing) {
            Object val = obj.get(field);
            if (val == null) {
                val = obj.get(field.toLowerCase());
            }
            if (val != null && !String.valueOf(val).isBlank()) {
                extracted.put(field, clamp(String.valueOf(val).trim(), 200));
                findings.add("LLM-extracted " + humanField(field) + ": " + clamp(String.valueOf(val).trim(), 120));
                filled = true;
            }
        }
        if (filled) {
            v.setLlmDrafted(true);
            v.setLlmModel(r.model());
        }
    }

    private LlmResult safeComplete(LlmRequest req) {
        try {
            LlmResult r = llm.complete(req);
            return r == null ? LlmResult.notConfigured() : r;
        } catch (Exception e) {
            return LlmResult.failed(e.getMessage());
        }
    }

    /** Lenient JSON-object parse: strips code fences and tolerates prose around the object. */
    private static Map<String, Object> parseJsonObject(String text) {
        if (text == null) return null;
        String t = text.strip();
        if (t.startsWith("```")) {
            int nl = t.indexOf('\n');
            if (nl > 0) t = t.substring(nl + 1);
            if (t.endsWith("```")) t = t.substring(0, t.length() - 3);
            t = t.strip();
        }
        int brace = t.indexOf('{');
        int lastBrace = t.lastIndexOf('}');
        if (brace < 0 || lastBrace < brace) return null;
        t = t.substring(brace, lastBrace + 1);
        try {
            return JSON.readValue(t, new TypeReference<LinkedHashMap<String, Object>>() { });
        } catch (Exception ex) {
            return null;
        }
    }
}
