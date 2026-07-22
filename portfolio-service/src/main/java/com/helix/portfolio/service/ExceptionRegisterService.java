package com.helix.portfolio.service;

import com.helix.common.audit.AuditService;
import com.helix.common.web.ApiException;
import com.helix.portfolio.client.PortfolioUpstreamClient;
import com.helix.portfolio.dto.ExceptionDtos.ExceptionItem;
import com.helix.portfolio.dto.ExceptionDtos.RollupResult;
import com.helix.portfolio.entity.EwsSignal;
import com.helix.portfolio.entity.Tickler;
import com.helix.portfolio.repo.TicklerRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Unified exception / tickler register (U7).
 *
 * <p>The {@link #rollup} is a READ-ONLY cockpit: it aggregates open exception items across
 * the platform — covenant breaches / overdue tests, MER overdue / deferred documents,
 * pending CAD deviations, expiring limits and open EWS signals — into one normalised shape.
 * Every read is best-effort: a source that is unreachable degrades to a warning in the
 * response rather than failing the whole rollup, and NO source of record is ever mutated
 * by surfacing its items here.
 *
 * <p>Alongside the aggregation it owns a light manual {@link Tickler} entity so a human can
 * raise, assign and resolve a follow-up. Resolution enforces segregation of duties: the
 * resolver must differ from the assigned owner.
 */
@Service
public class ExceptionRegisterService {

    private static final int HORIZON_DAYS = 60;
    private static final String SUBJECT = "Tickler";

    /** Statuses considered "open" (still an exception to work) per source. */
    private static final List<String> COVENANT_OPEN = List.of("BREACHED", "OVERDUE", "SCHEDULED");
    private static final List<String> MER_OPEN = List.of("OPEN", "SUBMITTED", "OVERDUE", "ESCALATED");
    private static final List<String> CAD_OPEN = List.of("CHECKLIST", "IN_PROGRESS", "DEVIATION");

    private final TicklerRepository ticklers;
    private final PortfolioUpstreamClient upstream;
    private final EwsService ews;
    private final AuditService audit;

    public ExceptionRegisterService(TicklerRepository ticklers, PortfolioUpstreamClient upstream,
                                    EwsService ews, AuditService audit) {
        this.ticklers = ticklers;
        this.upstream = upstream;
        this.ews = ews;
        this.audit = audit;
    }

    // ---------------------------------------------------------------- rollup (read-only)

    @Transactional(readOnly = true)
    public RollupResult rollup(String subjectRef) {
        String subject = subjectRef == null || subjectRef.isBlank() ? null : subjectRef.trim();
        List<ExceptionItem> items = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        addCovenants(subject, items, warnings);
        addMer(subject, items, warnings);
        addCad(subject, items, warnings);
        addLimits(subject, items, warnings);
        addEws(subject, items, warnings);

        // Most severe first, then earliest due date (nulls last), then source.
        items.sort(Comparator
                .comparingInt((ExceptionItem i) -> severityRank(i.severity())).reversed()
                .thenComparing(i -> i.dueAt() == null ? "9999-12-31" : i.dueAt())
                .thenComparing(ExceptionItem::source));

        Map<String, Integer> bySource = new LinkedHashMap<>();
        for (ExceptionItem i : items) {
            bySource.merge(i.source(), 1, Integer::sum);
        }
        return new RollupResult(subject, items.size(), items, bySource, warnings);
    }

    private void addCovenants(String subject, List<ExceptionItem> out, List<String> warnings) {
        try {
            List<Map<String, Object>> rows = subject != null
                    ? upstream.getListOrThrow("decision", "/api/covenants/tracking/{r}", subject)
                    : upstream.getListOrThrow("decision", "/api/covenants/tracking/upcoming?days=" + HORIZON_DAYS);
            for (Map<String, Object> r : rows) {
                String status = str(r, "status");
                if (!COVENANT_OPEN.contains(status)) continue;
                String metric = str(r, "metric");
                String severity = "SCHEDULED".equals(status) ? "LOW" : "HIGH";
                out.add(new ExceptionItem("COVENANT", status, str(r, "applicationReference"),
                        "Covenant " + orDash(metric) + " " + status.toLowerCase(),
                        null, str(r, "currentDueDate"), severity, status));
            }
        } catch (Exception e) {
            warnings.add("covenant-tracking source unavailable: " + e.getMessage());
        }
    }

    private void addMer(String subject, List<ExceptionItem> out, List<String> warnings) {
        try {
            List<Map<String, Object>> rows = subject != null
                    ? upstream.getListOrThrow("decision", "/api/mer/{r}", subject)
                    : upstream.getListOrThrow("decision", "/api/mer/upcoming?days=" + HORIZON_DAYS);
            for (Map<String, Object> r : rows) {
                String status = str(r, "status");
                if (!MER_OPEN.contains(status)) continue;
                String criticality = str(r, "criticality");
                String severity = ("OVERDUE".equals(status) || "ESCALATED".equals(status))
                        ? "HIGH" : (criticality == null ? "MEDIUM" : criticality);
                out.add(new ExceptionItem("MER", str(r, "itemType"), str(r, "applicationReference"),
                        orDash(str(r, "description")), str(r, "owner"), str(r, "dueDate"), severity, status));
            }
        } catch (Exception e) {
            warnings.add("MER source unavailable: " + e.getMessage());
        }
    }

    private void addCad(String subject, List<ExceptionItem> out, List<String> warnings) {
        try {
            List<Map<String, Object>> rows = upstream.getListOrThrow("decision", "/api/cad/cases");
            for (Map<String, Object> r : rows) {
                String status = str(r, "status");
                if (!CAD_OPEN.contains(status)) continue;
                String appRef = str(r, "applicationRef");
                if (subject != null && !subject.equalsIgnoreCase(appRef)) continue;
                String severity = "DEVIATION".equals(status) ? "HIGH" : "MEDIUM";
                out.add(new ExceptionItem("CAD", "CAD_" + status, appRef,
                        "CAD case " + orDash(str(r, "cpType")) + " — " + status,
                        str(r, "createdBy"), null, severity, status));
            }
        } catch (Exception e) {
            warnings.add("CAD source unavailable: " + e.getMessage());
        }
    }

    private void addLimits(String subject, List<ExceptionItem> out, List<String> warnings) {
        // Expiring limits are only cheaply listable per deal; a subject-less rollup skips
        // this source (no items, no warning) rather than scanning the whole tree.
        if (subject == null) return;
        try {
            List<Map<String, Object>> rows =
                    upstream.getListOrThrow("limit", "/api/limits/by-application?applicationRef={r}", subject);
            LocalDate today = LocalDate.now();
            LocalDate horizon = today.plusDays(HORIZON_DAYS);
            for (Map<String, Object> r : rows) {
                if ("CLOSED".equals(str(r, "status"))) continue;
                LocalDate expiry = parseDate(str(r, "expiryDate"));
                if (expiry == null || expiry.isAfter(horizon)) continue;   // only near / past expiry
                String severity = expiry.isBefore(today) ? "HIGH"
                        : (expiry.isBefore(today.plusDays(30)) ? "MEDIUM" : "LOW");
                out.add(new ExceptionItem("LIMIT", "LIMIT_EXPIRING", orDash(str(r, "applicationRef")),
                        "Limit " + orDash(str(r, "code")) + " / " + orDash(str(r, "reference"))
                                + (expiry.isBefore(today) ? " expired" : " expiring"),
                        null, str(r, "expiryDate"), severity, str(r, "status")));
            }
        } catch (Exception e) {
            warnings.add("limit source unavailable: " + e.getMessage());
        }
    }

    private void addEws(String subject, List<ExceptionItem> out, List<String> warnings) {
        try {
            List<EwsSignal> signals = subject != null ? ews.forApplication(subject) : ews.watchlist();
            for (EwsSignal s : signals) {
                if (!"OPEN".equals(s.getStatus())) continue;
                out.add(new ExceptionItem("EWS", s.getSignalType(), s.getApplicationReference(),
                        orDash(s.getRationale()), null, null, orDash(s.getSeverity()), s.getStatus()));
            }
        } catch (Exception e) {
            warnings.add("EWS source unavailable: " + e.getMessage());
        }
    }

    // ---------------------------------------------------------------- ticklers (human-owned)

    @Transactional
    public Tickler create(String subjectRef, String title, String description, String owner,
                          LocalDate dueAt, String priority, String actor) {
        Tickler t = new Tickler();
        t.setTicklerRef(newRef());
        t.setSubjectRef(subjectRef.trim());
        t.setTitle(title.trim());
        t.setDescription(description == null ? null : description.trim());
        t.setOwner(owner == null || owner.isBlank() ? null : owner.trim());
        t.setDueAt(dueAt);
        t.setPriority(priority == null || priority.isBlank() ? "MEDIUM" : priority.trim().toUpperCase());
        t.setStatus(t.getOwner() == null ? "OPEN" : "IN_PROGRESS");
        t.setCreatedBy(actor);
        Tickler saved = ticklers.save(t);
        audit.human(actor, "TICKLER_CREATED", SUBJECT, saved.getTicklerRef(),
                "Tickler raised against %s: %s".formatted(saved.getSubjectRef(), saved.getTitle()),
                Map.of("subjectRef", saved.getSubjectRef(), "priority", saved.getPriority(),
                        "owner", saved.getOwner() == null ? "" : saved.getOwner()));
        return saved;
    }

    @Transactional
    public Tickler assign(String ticklerRef, String toActor, String actor) {
        Tickler t = get(ticklerRef);
        if (toActor == null || toActor.isBlank()) {
            throw ApiException.badRequest("toActor is required");
        }
        if ("RESOLVED".equals(t.getStatus())) {
            throw ApiException.conflict("Tickler " + ticklerRef + " is already RESOLVED");
        }
        t.setOwner(toActor.trim());
        t.setStatus("IN_PROGRESS");
        Tickler saved = ticklers.save(t);
        audit.human(actor, "TICKLER_ASSIGNED", SUBJECT, saved.getTicklerRef(),
                "Tickler assigned to " + saved.getOwner(), Map.of("owner", saved.getOwner()));
        return saved;
    }

    @Transactional
    public Tickler resolve(String ticklerRef, String note, String actor) {
        Tickler t = get(ticklerRef);
        if ("RESOLVED".equals(t.getStatus())) {
            throw ApiException.conflict("Tickler " + ticklerRef + " is already RESOLVED");
        }
        if (t.getOwner() != null && t.getOwner().equalsIgnoreCase(actor)) {
            throw ApiException.forbiddenAutonomy(
                    "Resolver must differ from the assigned owner (segregation of duties)");
        }
        t.setStatus("RESOLVED");
        t.setResolvedBy(actor);
        t.setResolvedNote(note == null ? null : note.trim());
        t.setResolvedAt(Instant.now());
        Tickler saved = ticklers.save(t);
        audit.human(actor, "TICKLER_RESOLVED", SUBJECT, saved.getTicklerRef(),
                "Tickler resolved: " + (note == null ? "" : note), Map.of("resolvedBy", actor));
        return saved;
    }

    @Transactional(readOnly = true)
    public List<Tickler> list(String status, String subjectRef) {
        if (subjectRef != null && !subjectRef.isBlank()) {
            return ticklers.findBySubjectRefOrderByCreatedAtDesc(subjectRef.trim());
        }
        if (status != null && !status.isBlank()) {
            return ticklers.findByStatusOrderByCreatedAtDesc(status.trim().toUpperCase());
        }
        return ticklers.findByOrderByCreatedAtDesc();
    }

    @Transactional(readOnly = true)
    public Tickler get(String ticklerRef) {
        return ticklers.findByTicklerRef(ticklerRef)
                .orElseThrow(() -> ApiException.notFound("No tickler: " + ticklerRef));
    }

    // ---------------------------------------------------------------- helpers

    private static int severityRank(String severity) {
        return switch (severity == null ? "" : severity.toUpperCase()) {
            case "SEVERE" -> 4;
            case "HIGH" -> 3;
            case "MEDIUM" -> 2;
            case "LOW" -> 1;
            default -> 0;
        };
    }

    private static String str(Map<String, Object> m, String key) {
        Object v = m == null ? null : m.get(key);
        return v == null ? null : String.valueOf(v);
    }

    private static String orDash(String s) {
        return s == null || s.isBlank() ? "—" : s;
    }

    private static LocalDate parseDate(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return LocalDate.parse(s.length() > 10 ? s.substring(0, 10) : s);
        } catch (Exception e) {
            return null;
        }
    }

    private static String newRef() {
        final String alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
        StringBuilder sb = new StringBuilder("TKL-");
        for (int i = 0; i < 6; i++) {
            sb.append(alphabet.charAt(ThreadLocalRandom.current().nextInt(alphabet.length())));
        }
        return sb.toString();
    }
}
