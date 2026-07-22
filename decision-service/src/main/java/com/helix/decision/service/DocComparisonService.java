package com.helix.decision.service;

import com.helix.common.audit.AuditService;
import com.helix.common.web.ApiException;
import com.helix.decision.dto.DocCompareDtos.ComparisonView;
import com.helix.decision.dto.DocCompareDtos.DiffRow;
import com.helix.decision.entity.CreditProposal;
import com.helix.decision.entity.DocComparison;
import com.helix.decision.entity.DocComparison.ChangeType;
import com.helix.decision.entity.DocComparison.Kind;
import com.helix.decision.entity.GeneratedDocument;
import com.helix.decision.repo.CreditProposalRepository;
import com.helix.decision.repo.DocComparisonRepository;
import com.helix.decision.repo.GeneratedDocumentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Deterministic document-comparison engine (CLoM F57). Reads two versioned artifacts that
 * already live in decision-service — two {@link CreditProposal} versions or two
 * {@link GeneratedDocument} rows — parses each into ordered sections, and emits a structured
 * change table ({@code ADDED / REMOVED / CHANGED / UNCHANGED}).
 *
 * <p><b>Governance:</b> the engine is strictly read-only over the sources — it never mutates a
 * proposal or a document — and is fully deterministic: the same two source artifacts always
 * yield a byte-identical {@code diff}. The persisted {@link DocComparison} is advisory and
 * stamped {@code audit.engine}.
 */
@Service
public class DocComparisonService {

    private final DocComparisonRepository comparisons;
    private final CreditProposalRepository proposals;
    private final GeneratedDocumentRepository documents;
    private final AuditService audit;

    public DocComparisonService(DocComparisonRepository comparisons, CreditProposalRepository proposals,
                                GeneratedDocumentRepository documents, AuditService audit) {
        this.comparisons = comparisons;
        this.proposals = proposals;
        this.documents = documents;
        this.audit = audit;
    }

    // ----------------------------------------------------------------- compute

    /**
     * Compute + persist a comparison of the two selected artifacts and return the change table.
     * Read-only over the sources; deterministic in the diff.
     */
    @Transactional
    public ComparisonView compare(String kindRaw, String subjectRef, String leftRef, String rightRef, String actor) {
        Kind kind;
        try {
            kind = Kind.parse(kindRaw);
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("Unknown comparison kind: " + kindRaw
                    + " (expected PROPOSAL_VERSIONS | DOCUMENT_VERSIONS)");
        }
        if (subjectRef == null || subjectRef.isBlank()) {
            throw ApiException.badRequest("subjectRef is required");
        }
        if (leftRef == null || leftRef.isBlank() || rightRef == null || rightRef.isBlank()) {
            throw ApiException.badRequest("leftRef and rightRef are required");
        }

        Sides sides = switch (kind) {
            case PROPOSAL_VERSIONS -> proposalSides(subjectRef.trim(), leftRef.trim(), rightRef.trim());
            case DOCUMENT_VERSIONS -> documentSides(leftRef.trim(), rightRef.trim());
        };

        List<DiffRow> rows = diff(sides.left(), sides.right());
        int added = 0, removed = 0, changed = 0, unchanged = 0;
        List<Map<String, Object>> rowMaps = new ArrayList<>();
        for (DiffRow r : rows) {
            switch (ChangeType.valueOf(r.changeType())) {
                case ADDED -> added++;
                case REMOVED -> removed++;
                case CHANGED -> changed++;
                case UNCHANGED -> unchanged++;
            }
            rowMaps.add(rowToMap(r));
        }

        DocComparison c = new DocComparison();
        c.setComparisonRef(generateRef());
        c.setKind(kind.name());
        c.setSubjectRef(subjectRef.trim());
        c.setLeftRef(leftRef.trim());
        c.setRightRef(rightRef.trim());
        c.setLeftLabel(sides.leftLabel());
        c.setRightLabel(sides.rightLabel());
        c.setAdded(added);
        c.setRemoved(removed);
        c.setChanged(changed);
        c.setUnchanged(unchanged);
        Map<String, Object> diffMap = new LinkedHashMap<>();
        diffMap.put("rows", rowMaps);
        c.setDiff(diffMap);
        c.setAdvisory(true);
        c.setCreatedBy(actor);
        DocComparison saved = comparisons.save(c);

        audit.engine("DOC_COMPARISON_COMPUTED", "Application", subjectRef.trim(),
                "Compared %s %s vs %s — %d added, %d removed, %d changed, %d unchanged"
                        .formatted(kind.name(), leftRef.trim(), rightRef.trim(), added, removed, changed, unchanged),
                Map.of("comparisonRef", saved.getComparisonRef(), "kind", kind.name(),
                        "leftRef", leftRef.trim(), "rightRef", rightRef.trim(),
                        "added", added, "removed", removed, "changed", changed, "unchanged", unchanged,
                        "advisory", true));
        return toView(saved);
    }

    @Transactional(readOnly = true)
    public ComparisonView get(String comparisonRef) {
        DocComparison c = comparisons.findByComparisonRef(comparisonRef)
                .orElseThrow(() -> ApiException.notFound("No comparison: " + comparisonRef));
        return toView(c);
    }

    @Transactional(readOnly = true)
    public List<ComparisonView> listBySubject(String subjectRef) {
        List<ComparisonView> out = new ArrayList<>();
        for (DocComparison c : comparisons.findBySubjectRefOrderByIdDesc(subjectRef)) {
            out.add(toView(c));
        }
        return out;
    }

    // ----------------------------------------------------------------- source resolution

    /** Left/right section maps + their deterministic human labels. */
    private record Sides(Map<String, String> left, Map<String, String> right, String leftLabel, String rightLabel) {
    }

    private Sides proposalSides(String subjectRef, String leftRef, String rightRef) {
        int leftVersion = parseVersion(leftRef);
        int rightVersion = parseVersion(rightRef);
        CreditProposal left = proposals.findByApplicationReferenceAndVersion(subjectRef, leftVersion)
                .orElseThrow(() -> ApiException.notFound(
                        "No credit proposal v" + leftVersion + " for " + subjectRef));
        CreditProposal right = proposals.findByApplicationReferenceAndVersion(subjectRef, rightVersion)
                .orElseThrow(() -> ApiException.notFound(
                        "No credit proposal v" + rightVersion + " for " + subjectRef));
        return new Sides(
                parseProposalSections(left.getMarkdown()),
                parseProposalSections(right.getMarkdown()),
                "Proposal v" + left.getVersion() + " (" + nv(left.getFormat()) + ")",
                "Proposal v" + right.getVersion() + " (" + nv(right.getFormat()) + ")");
    }

    private Sides documentSides(String leftRef, String rightRef) {
        long leftId = parseId(leftRef);
        long rightId = parseId(rightRef);
        GeneratedDocument left = documents.findById(leftId)
                .orElseThrow(() -> ApiException.notFound("No generated document: " + leftId));
        GeneratedDocument right = documents.findById(rightId)
                .orElseThrow(() -> ApiException.notFound("No generated document: " + rightId));
        return new Sides(
                parseDocumentSections(left),
                parseDocumentSections(right),
                "Document #" + left.getId() + " · " + nv(left.getTitle()),
                "Document #" + right.getId() + " · " + nv(right.getTitle()));
    }

    private static int parseVersion(String ref) {
        try {
            return Integer.parseInt(ref.trim());
        } catch (NumberFormatException e) {
            throw ApiException.badRequest("PROPOSAL_VERSIONS leftRef/rightRef must be version numbers, got: " + ref);
        }
    }

    private static long parseId(String ref) {
        try {
            return Long.parseLong(ref.trim());
        } catch (NumberFormatException e) {
            throw ApiException.badRequest("DOCUMENT_VERSIONS leftRef/rightRef must be document ids, got: " + ref);
        }
    }

    // ----------------------------------------------------------------- deterministic diff

    /**
     * Section-wise diff over the ordered union of section keys: every left section in order,
     * then any right-only section in right order. Present on both + equal body -> UNCHANGED;
     * present on both + different body -> CHANGED; left-only -> REMOVED; right-only -> ADDED.
     */
    private List<DiffRow> diff(Map<String, String> left, Map<String, String> right) {
        List<DiffRow> rows = new ArrayList<>();
        for (Map.Entry<String, String> e : left.entrySet()) {
            String key = e.getKey();
            String oldValue = e.getValue();
            if (right.containsKey(key)) {
                String newValue = right.get(key);
                if (oldValue.equals(newValue)) {
                    rows.add(new DiffRow(key, ChangeType.UNCHANGED.name(), oldValue, newValue));
                } else {
                    rows.add(new DiffRow(key, ChangeType.CHANGED.name(), oldValue, newValue));
                }
            } else {
                rows.add(new DiffRow(key, ChangeType.REMOVED.name(), oldValue, null));
            }
        }
        for (Map.Entry<String, String> e : right.entrySet()) {
            if (!left.containsKey(e.getKey())) {
                rows.add(new DiffRow(e.getKey(), ChangeType.ADDED.name(), null, e.getValue()));
            }
        }
        return rows;
    }

    /**
     * Parse a proposal's markdown into an ordered {@code heading -> body} map. Each {@code ## }
     * line starts a section (keyed by the heading text); its body is every line up to the next
     * {@code ## } heading, trimmed. Content before the first heading (the h1 title + muted
     * preamble) is ignored — it is not a comparable section.
     */
    private static Map<String, String> parseProposalSections(String markdown) {
        Map<String, String> sections = new LinkedHashMap<>();
        if (markdown == null) return sections;
        String current = null;
        StringBuilder body = new StringBuilder();
        for (String line : markdown.split("\n", -1)) {
            if (line.startsWith("## ")) {
                if (current != null) {
                    sections.put(current, body.toString().strip());
                }
                current = line.substring(3).strip();
                body.setLength(0);
            } else if (current != null) {
                body.append(line).append("\n");
            }
        }
        if (current != null) {
            sections.put(current, body.toString().strip());
        }
        return sections;
    }

    /**
     * Parse a generated document into an ordered {@code clauseTitle -> clauseText} map, in the
     * document's clause order. The clause title (falling back to the humanised clause ref) is the
     * comparable section key so a diff reads as clause-level changes.
     */
    @SuppressWarnings("unchecked")
    private static Map<String, String> parseDocumentSections(GeneratedDocument doc) {
        Map<String, String> sections = new LinkedHashMap<>();
        List<String> order = doc.getClauseOrder() == null ? List.of() : doc.getClauseOrder();
        Map<String, Object> clauses = doc.getClauses() == null ? Map.of() : doc.getClauses();
        for (String ref : order) {
            Object raw = clauses.get(ref);
            String title = ref;
            String text = "";
            if (raw instanceof Map<?, ?> m) {
                Map<String, Object> row = (Map<String, Object>) m;
                Object t = row.get("title");
                if (t != null && !String.valueOf(t).isBlank()) title = String.valueOf(t);
                Object body = row.get("text");
                if (body != null) text = String.valueOf(body);
            }
            // Guard against duplicate titles collapsing sections — disambiguate with the clause ref.
            String key = sections.containsKey(title) ? title + " [" + ref + "]" : title;
            sections.put(key, text.strip());
        }
        return sections;
    }

    // ----------------------------------------------------------------- mapping helpers

    private static Map<String, Object> rowToMap(DiffRow r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("section", r.section());
        m.put("changeType", r.changeType());
        m.put("oldValue", r.oldValue());
        m.put("newValue", r.newValue());
        return m;
    }

    @SuppressWarnings("unchecked")
    private static ComparisonView toView(DocComparison c) {
        List<DiffRow> rows = new ArrayList<>();
        Map<String, Object> diff = c.getDiff();
        if (diff != null && diff.get("rows") instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof Map<?, ?> m) {
                    Map<String, Object> row = (Map<String, Object>) m;
                    rows.add(new DiffRow(
                            str(row.get("section")),
                            str(row.get("changeType")),
                            row.get("oldValue") == null ? null : str(row.get("oldValue")),
                            row.get("newValue") == null ? null : str(row.get("newValue"))));
                }
            }
        }
        return new ComparisonView(c.getComparisonRef(), c.getKind(), c.getSubjectRef(),
                c.getLeftRef(), c.getRightRef(), c.getLeftLabel(), c.getRightLabel(),
                c.getAdded(), c.getRemoved(), c.getChanged(), c.getUnchanged(),
                c.isAdvisory(), c.getCreatedBy(), c.getCreatedAt(), rows);
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static String nv(String s) {
        return s == null || s.isBlank() ? "—" : s;
    }

    private String generateRef() {
        for (int i = 0; i < 8; i++) {
            String ref = "CMP-" + UUID.randomUUID().toString().replace("-", "")
                    .substring(0, 6).toUpperCase();
            if (!comparisons.existsByComparisonRef(ref)) {
                return ref;
            }
        }
        return "CMP-" + UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase();
    }
}
